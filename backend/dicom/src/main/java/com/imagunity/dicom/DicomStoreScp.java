package com.imagunity.dicom;

import com.imagunity.domain.exam.DicomUids;
import com.imagunity.domain.exam.ReceivedInstance;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomInputStream.IncludeBulkData;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.AssociationHandler;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.AAssociateAC;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.pdu.UserIdentityAC;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.DicomServiceRegistry;

/**
 * Entrée DICOM de la box : un SCP C-STORE (et C-ECHO) sur le réseau local.
 *
 * <p>Pour le PACS, la box est une destination DICOM ordinaire. Contrôles appliqués :
 * <ul>
 *   <li>l'AET appelant doit figurer dans la liste des émetteurs autorisés ;</li>
 *   <li>l'AET appelé doit être une destination configurée (routage par destination DICOM) ;</li>
 *   <li>les UID doivent être valides, et le SOP Instance UID du jeu de données doit
 *       correspondre à celui de la commande ;</li>
 *   <li>l'espace disque libre doit rester au-dessus du seuil configuré.</li>
 * </ul>
 * Les instances sont écrites telles que reçues (aucune modification), puis regroupées par étude.
 */
public final class DicomStoreScp implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DicomStoreScp.class.getName());

    private final DicomInputConfig config;
    private final IncomingStore store;
    private final StudyAssembler assembler;
    private Device device;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;

    public DicomStoreScp(DicomInputConfig config, StudyListener listener) {
        this(config, listener, Clock.systemUTC());
    }

    DicomStoreScp(DicomInputConfig config, StudyListener listener, Clock clock) {
        this.config = config;
        this.store = new IncomingStore(config.storageDir());
        this.assembler = new StudyAssembler(clock, config.studyIdleTimeout(), listener);
    }

    /** Reprend les études en attente sur le disque, puis ouvre le port DICOM. */
    public synchronized void start() throws IOException {
        if (executor != null) {
            return;
        }
        store.initialize();
        int recovered = 0;
        for (IncomingStore.Recovered r : store.recover()) {
            String destination = config.destinationFor(r.calledAet()).orElse(null);
            if (destination == null) {
                LOG.log(Level.WARNING, "Instances en attente pour la destination {0}, qui n''est plus configurée : ignorées",
                        r.calledAet());
                continue;
            }
            assembler.add(r.calledAet(), destination, null, r.studyInstanceUid(), r.instance());
            recovered++;
        }
        if (recovered > 0) {
            LOG.log(Level.INFO, "{0} instance(s) en attente reprise(s) dans {1} étude(s)", recovered,
                    assembler.pendingStudies());
        }

        // Appareil neuf à chaque démarrage : un redémarrage ne doit pas cumuler connexions et AE.
        device = new Device("imagunity-box");
        Connection connection = new Connection();
        connection.setPort(config.port());
        if (config.bindAddress() != null && !config.bindAddress().isBlank()) {
            connection.setBindAddress(config.bindAddress());
        }
        ApplicationEntity ae = new ApplicationEntity("*");
        ae.setAssociationAcceptor(true);
        ae.addConnection(connection);
        ae.addTransferCapability(new TransferCapability(null, "*", TransferCapability.Role.SCP, "*"));
        device.addConnection(connection);
        device.addApplicationEntity(ae);

        DicomServiceRegistry services = new DicomServiceRegistry();
        services.addDicomService(new BasicCEchoSCP());
        services.addDicomService(new StoreService());
        device.setDimseRQHandler(services);
        device.setAssociationHandler(new AccessControl());

        executor = Executors.newVirtualThreadPerTaskExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("dicom-scheduler").factory());
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduler);
        try {
            device.bindConnections();
        } catch (IOException | GeneralSecurityException e) {
            stop(); // libère les fils d'exécution : un nouvel essai de démarrage reste possible
            throw e instanceof IOException io ? io : new IOException("Ouverture du port DICOM impossible", e);
        }
        long period = Math.max(100, Math.min(1000, config.studyIdleTimeout().toMillis() / 4));
        scheduler.scheduleWithFixedDelay(assembler::flushIdle, period, period, TimeUnit.MILLISECONDS);
        LOG.log(Level.INFO, "Entrée DICOM ouverte sur le port {0} ; destinations : {1}", config.port(),
                config.routes().keySet());
    }

    /** Ferme le port DICOM. Les études en cours restent sur le disque et seront reprises au démarrage. */
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        device.unbindConnections();
        scheduler.shutdownNow();
        executor.shutdownNow();
        executor = null;
        scheduler = null;
        LOG.log(Level.INFO, "Entrée DICOM fermée");
    }

    @Override
    public void close() {
        stop();
    }

    /** Études en cours de réception (pas encore complètes). */
    public int pendingStudies() {
        return assembler.pendingStudies();
    }

    /** Refuse les émetteurs non autorisés et les destinations inconnues dès l'association. */
    private final class AccessControl extends AssociationHandler {

        @Override
        protected AAssociateAC makeAAssociateAC(Association as, AAssociateRQ rq, UserIdentityAC userIdentity)
                throws IOException {
            if (!config.isCallingAetAllowed(rq.getCallingAET())) {
                LOG.log(Level.WARNING, "Association refusée : émetteur {0} non autorisé (depuis {1})",
                        rq.getCallingAET(), as.getSocket().getInetAddress().getHostAddress());
                throw new AAssociateRJ(AAssociateRJ.RESULT_REJECTED_PERMANENT, AAssociateRJ.SOURCE_SERVICE_USER,
                        AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED);
            }
            if (config.destinationFor(rq.getCalledAET()).isEmpty()) {
                LOG.log(Level.WARNING, "Association refusée : destination {0} inconnue (émetteur {1})",
                        rq.getCalledAET(), rq.getCallingAET());
                throw new AAssociateRJ(AAssociateRJ.RESULT_REJECTED_PERMANENT, AAssociateRJ.SOURCE_SERVICE_USER,
                        AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED);
            }
            return super.makeAAssociateAC(as, rq, userIdentity);
        }
    }

    /** Réception C-STORE : écriture sur disque, contrôles, puis rattachement à l'étude. */
    private final class StoreService extends BasicCStoreSCP {

        StoreService() {
            super("*");
        }

        @Override
        protected void store(Association as, PresentationContext pc, Attributes rq, PDVInputStream data,
                             Attributes rsp) throws IOException {
            String calledAet = as.getCalledAET().trim();
            String destination = config.destinationFor(calledAet)
                    .orElseThrow(() -> new DicomServiceException(Status.ProcessingFailure, "Destination inconnue"));
            String cuid = rq.getString(Tag.AffectedSOPClassUID);
            String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
            String tsuid = pc.getTransferSyntax();
            if (!DicomUids.isValid(cuid) || !DicomUids.isValid(iuid) || !DicomUids.isValid(tsuid)) {
                throw new DicomServiceException(Status.CannotUnderstand, "UID de commande invalide");
            }
            if (Files.getFileStore(store.root()).getUsableSpace() < config.minFreeSpace()) {
                LOG.log(Level.ERROR, "Espace disque insuffisant : instance refusée");
                throw new DicomServiceException(Status.OutOfResources, "Espace disque insuffisant");
            }

            Path temp = store.newTempFile();
            try {
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(temp));
                     DicomOutputStream out = new DicomOutputStream(os, UID.ExplicitVRLittleEndian)) {
                    out.writeFileMetaInformation(as.createFileMetaInformation(iuid, cuid, tsuid));
                    data.copyTo(out);
                }
                Attributes dataset;
                try (DicomInputStream in = new DicomInputStream(temp.toFile())) {
                    in.setIncludeBulkData(IncludeBulkData.NO);
                    dataset = in.readDatasetUntilPixelData();
                }
                String studyUid = dataset.getString(Tag.StudyInstanceUID);
                String seriesUid = dataset.getString(Tag.SeriesInstanceUID);
                if (!DicomUids.isValid(studyUid) || !DicomUids.isValid(seriesUid)) {
                    throw new DicomServiceException(Status.CannotUnderstand, "Study ou Series Instance UID invalide");
                }
                if (!iuid.equals(dataset.getString(Tag.SOPInstanceUID))) {
                    throw new DicomServiceException(Status.CannotUnderstand,
                            "SOP Instance UID du jeu de données différent de celui de la commande");
                }
                Path target = store.finalPath(calledAet, studyUid, seriesUid, iuid);
                store.commit(temp, target);
                assembler.add(calledAet, destination, as.getCallingAET(), studyUid,
                        new ReceivedInstance(cuid, iuid, seriesUid, tsuid, target, Files.size(target)));
                LOG.log(Level.DEBUG, "Instance reçue de {0} pour {1}", as.getCallingAET(), calledAet);
            } catch (DicomServiceException e) {
                Files.deleteIfExists(temp);
                LOG.log(Level.WARNING, "Instance refusée (émetteur {0}) : {1}", as.getCallingAET(), e.getMessage());
                throw e;
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                LOG.log(Level.ERROR, "Échec de la réception d'une instance", e);
                throw new DicomServiceException(Status.ProcessingFailure, e);
            }
        }
    }

    /** Délai d'inactivité configuré (pour les journaux et la console). */
    public Duration studyIdleTimeout() {
        return config.studyIdleTimeout();
    }
}
