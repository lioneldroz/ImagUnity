package com.imagunity.dicom;

import com.imagunity.domain.exam.DicomUids;
import com.imagunity.domain.exam.ReceivedInstance;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
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
            String destination = r.destination() != null ? r.destination()
                    : config.destinationFor(r.calledAet()).orElse(null);
            if (destination == null || !config.isKnownCalledAet(r.calledAet())) {
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
        if (config.routingAet() != null) {
            LOG.log(Level.INFO, "Routage par champ DICOM sur {0} : {1}", config.routingAet(),
                    String.join(" ; ", config.rules().stream().map(RoutingRule::describe).toList()));
        }
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
            if (!config.isKnownCalledAet(rq.getCalledAET())) {
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
            if (!config.isKnownCalledAet(calledAet)) {
                throw new DicomServiceException(Status.ProcessingFailure, "Destination inconnue");
            }
            String cuid = rq.getString(Tag.AffectedSOPClassUID);
            String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
            String tsuid = pc.getTransferSyntax();
            if (!DicomUids.isValid(cuid) || !DicomUids.isValid(iuid) || !DicomUids.isValid(tsuid)) {
                throw new DicomServiceException(Status.CannotUnderstand, "UID de commande invalide");
            }
            requireFreeSpace();
            Path temp = store.newTempFile();
            try {
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(temp));
                     DicomOutputStream out = new DicomOutputStream(os, UID.ExplicitVRLittleEndian)) {
                    out.writeFileMetaInformation(as.createFileMetaInformation(iuid, cuid, tsuid));
                    data.copyTo(out);
                }
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                LOG.log(Level.ERROR, "Échec de la réception d'une instance", e);
                throw new DicomServiceException(Status.ProcessingFailure, e);
            }
            accept(as.getCallingAET(), calledAet, temp, cuid, iuid, tsuid, null);
        }
    }

    /**
     * Instance reçue par STOW-RS (DICOMweb) : même traitement qu'une instance reçue par C-STORE (contrôles, routage,
     * rattachement à l'étude), à partir d'un fichier DICOM Part 10.
     *
     * @param source    émetteur, pour les journaux et le suivi (par exemple {@code dicomweb:pacs})
     * @param calledAet destination DICOM désignée par l'URL (route fixe ou destination de routage)
     * @param studyUid  étude imposée par l'URL, ou {@code null}
     * @return l'instance acceptée
     * @throws Refused si l'instance est refusée ; son statut DICOM donne la cause
     */
    public Accepted storePart10(String source, String calledAet, InputStream part10, String studyUid)
            throws Refused {
        if (calledAet == null || !config.isKnownCalledAet(calledAet)) {
            throw new Refused(Status.ProcessingFailure, "Destination inconnue : " + calledAet, null, null);
        }
        try {
            requireFreeSpace();
        } catch (IOException e) {
            throw new Refused(Status.OutOfResources, e.getMessage(), null, null);
        }
        Path temp = store.newTempFile();
        Attributes fmi;
        try {
            Files.copy(part10, temp);
            try (DicomInputStream in = new DicomInputStream(temp.toFile())) {
                fmi = in.readFileMetaInformation();
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            throw new Refused(Status.CannotUnderstand, "Fichier DICOM illisible : " + e.getMessage(), null, null);
        }
        if (fmi == null) {
            deleteQuietly(temp);
            throw new Refused(Status.CannotUnderstand, "Pas un fichier DICOM Part 10 (méta-informations)", null,
                    null);
        }
        String cuid = fmi.getString(Tag.MediaStorageSOPClassUID);
        String iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID);
        String tsuid = fmi.getString(Tag.TransferSyntaxUID);
        if (!DicomUids.isValid(cuid) || !DicomUids.isValid(iuid) || !DicomUids.isValid(tsuid)) {
            deleteQuietly(temp);
            throw new Refused(Status.CannotUnderstand, "UID des méta-informations invalide", null, null);
        }
        try {
            return accept(source, calledAet.trim(), temp, cuid, iuid, tsuid, studyUid);
        } catch (DicomServiceException e) {
            throw new Refused(e.getStatus(), e.getMessage(), cuid, iuid);
        }
    }

    /** Instance refusée par STOW-RS, avec ses UID s'ils ont pu être lus. */
    public static final class Refused extends DicomServiceException {

        private final String sopClassUid;
        private final String sopInstanceUid;

        Refused(int status, String message, String sopClassUid, String sopInstanceUid) {
            super(status, message);
            this.sopClassUid = sopClassUid;
            this.sopInstanceUid = sopInstanceUid;
        }

        public String sopClassUid() {
            return sopClassUid;
        }

        public String sopInstanceUid() {
            return sopInstanceUid;
        }
    }

    /** Failure Reason STOW-RS : instance d'une autre étude que celle imposée par l'URL. */
    static final int STUDY_MISMATCH = 0xA900;

    /**
     * Instance acceptée.
     *
     * @param sopClassUid    classe SOP
     * @param sopInstanceUid instance
     * @param studyUid       étude
     * @param destinations   partenaires destinataires
     */
    public record Accepted(String sopClassUid, String sopInstanceUid, String studyUid, List<String> destinations) {
    }

    private void requireFreeSpace() throws IOException {
        if (Files.getFileStore(store.root()).getUsableSpace() < config.minFreeSpace()) {
            LOG.log(Level.ERROR, "Espace disque insuffisant : instance refusée");
            throw new DicomServiceException(Status.OutOfResources, "Espace disque insuffisant");
        }
    }

    /** Contrôle une instance écrite dans {@code temp} (Part 10), la range pour chaque partenaire et la rattache. */
    private Accepted accept(String source, String calledAet, Path temp, String cuid, String iuid, String tsuid,
                            String expectedStudy) throws DicomServiceException {
        try {
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
            if (expectedStudy != null && !expectedStudy.equals(studyUid)) {
                throw new DicomServiceException(STUDY_MISMATCH, "Instance d'une autre étude que celle de l'URL");
            }
            if (!iuid.equals(dataset.getString(Tag.SOPInstanceUID))) {
                throw new DicomServiceException(Status.CannotUnderstand,
                        "SOP Instance UID du jeu de données différent de celui de la commande");
            }
            List<String> destinations = destinations(calledAet, dataset);
            Path first = store.finalPath(calledAet, destinations.getFirst(), studyUid, seriesUid, iuid);
            store.commit(temp, first);
            for (String destination : destinations) {
                Path target = store.finalPath(calledAet, destination, studyUid, seriesUid, iuid);
                if (!target.equals(first)) {
                    store.copy(first, target);
                }
                assembler.add(calledAet, destination, source, studyUid,
                        new ReceivedInstance(cuid, iuid, seriesUid, tsuid, target, Files.size(target)));
            }
            LOG.log(Level.DEBUG, "Instance reçue de {0} pour {1} → {2}", source, calledAet, destinations);
            return new Accepted(cuid, iuid, studyUid, destinations);
        } catch (DicomServiceException e) {
            deleteQuietly(temp);
            LOG.log(Level.WARNING, "Instance refusée (émetteur {0}) : {1}", source, e.getMessage());
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            LOG.log(Level.ERROR, "Échec de la réception d'une instance", e);
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Fichier temporaire non supprimé : " + file, e);
        }
    }

    /**
     * Partenaires d'une instance : celui de la route fixe, ou ceux de la première règle qui correspond.
     *
     * @throws DicomServiceException si aucune règle ne correspond : l'instance est refusée, le PACS la garde en échec
     */
    private List<String> destinations(String calledAet, Attributes dataset) throws DicomServiceException {
        Optional<String> fixed = config.destinationFor(calledAet);
        if (fixed.isPresent()) {
            return List.of(fixed.get());
        }
        RoutingRule rule = config.ruleFor(dataset).orElseThrow(() -> new DicomServiceException(
                Status.ProcessingFailure, "Aucune règle de routage ne correspond à cette instance"));
        LOG.log(Level.DEBUG, "Règle de routage {0} appliquée", rule.name());
        return rule.destinations();
    }

    /** Délai d'inactivité configuré (pour les journaux et la console). */
    public Duration studyIdleTimeout() {
        return config.studyIdleTimeout();
    }
}
