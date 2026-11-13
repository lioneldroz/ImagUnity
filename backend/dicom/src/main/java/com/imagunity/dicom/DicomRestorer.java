package com.imagunity.dicom;

import com.imagunity.domain.exam.DicomUids;
import com.imagunity.domain.exam.StudyRestorer;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomInputStream.IncludeBulkData;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.IncompatibleConnectionException;
import org.dcm4che3.net.InputStreamDataWriter;
import org.dcm4che3.net.Priority;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;

/**
 * Restitue une étude reçue au PACS local, en deux temps :
 * <ol>
 *   <li><b>validation de toutes les instances</b> : fichier DICOM Part 10 lisible de bout en bout,
 *       UID valides, et identifiants (étude, série, instance, classe SOP) conformes au manifeste.
 *       Une seule instance invalide et rien n'est envoyé ;</li>
 *   <li><b>envoi C-STORE</b> dans une association, chaque instance dans sa syntaxe de transfert d'origine
 *       (aucune conversion, aucune perte), lue en flux depuis le fichier.</li>
 * </ol>
 * Les statuts d'avertissement du PACS (classe {@code Bxxx}) sont acceptés ; tout autre statut fait échouer
 * la restitution.
 */
public final class DicomRestorer implements StudyRestorer {

    private static final System.Logger LOG = System.getLogger(DicomRestorer.class.getName());
    private static final int MAX_PRESENTATION_CONTEXTS = 127;

    /** Instance validée, prête à être envoyée. */
    private record Validated(Instance instance, String sopClassUid, String transferSyntaxUid, long datasetOffset) {
    }

    private record Pc(String sopClassUid, String transferSyntaxUid) {
    }

    private final RestoreConfig config;

    /**
     * Délais d'attente du PACS. Sans eux (0 par défaut dans dcm4che : attente sans fin), un PACS qui accepte la
     * connexion mais ne répond plus bloquerait l'orchestrateur, et avec lui tous les envois et réceptions de la box.
     *
     * @param connect  connexion TCP
     * @param accept   réponse à la demande d'association
     * @param response réponse à chaque C-STORE (une instance volumineuse peut être longue à enregistrer)
     * @param release  fin de l'association
     */
    record Timeouts(java.time.Duration connect, java.time.Duration accept, java.time.Duration response,
                    java.time.Duration release) {

        static final Timeouts DEFAULT = new Timeouts(java.time.Duration.ofSeconds(30),
                java.time.Duration.ofSeconds(30), java.time.Duration.ofMinutes(5), java.time.Duration.ofSeconds(30));
    }

    private final Timeouts timeouts;

    public DicomRestorer(RestoreConfig config) {
        this(config, Timeouts.DEFAULT);
    }

    DicomRestorer(RestoreConfig config, Timeouts timeouts) {
        this.config = Objects.requireNonNull(config, "config");
        this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    }

    @Override
    public void restore(String studyInstanceUid, List<Instance> instances) throws IOException {
        if (instances.isEmpty()) {
            throw new DicomValidationException("Étude sans instance");
        }
        List<Validated> validated = new ArrayList<>();
        for (Instance instance : instances) {
            validated.add(validate(studyInstanceUid, instance));
        }
        send(validated);
        LOG.log(Level.INFO, "Étude restituée à {0} : {1} instance(s)", config.calledAet(), validated.size());
    }

    private static Validated validate(String studyUid, Instance instance) throws DicomValidationException {
        try (DicomInputStream in = new DicomInputStream(instance.file().toFile())) {
            Attributes fmi = in.readFileMetaInformation();
            if (fmi == null) {
                throw new DicomValidationException("Méta-informations DICOM absentes");
            }
            long offset = in.getPosition();
            String cuid = fmi.getString(Tag.MediaStorageSOPClassUID);
            String iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID);
            String tsuid = fmi.getString(Tag.TransferSyntaxUID);
            if (!DicomUids.isValid(cuid) || !DicomUids.isValid(iuid) || !DicomUids.isValid(tsuid)) {
                throw new DicomValidationException("UID invalide dans les méta-informations");
            }
            // Lecture complète du jeu de données (sans conserver les pixels) : un fichier tronqué
            // ou mal formé est refusé ici, pas par le PACS.
            in.setIncludeBulkData(IncludeBulkData.NO);
            Attributes ds = in.readDataset();
            require(iuid.equals(instance.sopInstanceUid()), "SOP Instance UID différent du manifeste");
            require(iuid.equals(ds.getString(Tag.SOPInstanceUID)), "SOP Instance UID incohérent dans le fichier");
            require(cuid.equals(ds.getString(Tag.SOPClassUID)), "SOP Class UID incohérent dans le fichier");
            require(studyUid.equals(ds.getString(Tag.StudyInstanceUID)), "Study Instance UID différent du manifeste");
            require(instance.seriesInstanceUid().equals(ds.getString(Tag.SeriesInstanceUID)),
                    "Series Instance UID différent du manifeste");
            return new Validated(instance, cuid, tsuid, offset);
        } catch (DicomValidationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new DicomValidationException("Instance DICOM illisible ou mal formée", e);
        }
    }

    private static void require(boolean condition, String message) throws DicomValidationException {
        if (!condition) {
            throw new DicomValidationException(message);
        }
    }

    private void send(List<Validated> instances) throws IOException {
        Map<Pc, Integer> contexts = new LinkedHashMap<>();
        for (Validated v : instances) {
            contexts.putIfAbsent(new Pc(v.sopClassUid(), v.transferSyntaxUid()), contexts.size() * 2 + 1);
        }
        if (contexts.size() > MAX_PRESENTATION_CONTEXTS) {
            throw new IOException("Trop de combinaisons classe SOP / syntaxe de transfert pour une association");
        }
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(config.calledAet());
        contexts.forEach((pc, id) -> rq.addPresentationContext(
                new PresentationContext(id, pc.sopClassUid(), pc.transferSyntaxUid())));

        Device device = new Device("imagunity-restore");
        Connection local = new Connection();
        local.setConnectTimeout(Math.toIntExact(timeouts.connect().toMillis()));
        local.setAcceptTimeout(Math.toIntExact(timeouts.accept().toMillis()));
        local.setResponseTimeout(Math.toIntExact(timeouts.response().toMillis()));
        local.setReleaseTimeout(Math.toIntExact(timeouts.release().toMillis()));
        ApplicationEntity ae = new ApplicationEntity(config.callingAet());
        device.addConnection(local);
        device.addApplicationEntity(ae);
        ae.addConnection(local);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("dicom-restore").factory());
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduler);
        Connection remote = new Connection();
        remote.setHostname(config.host());
        remote.setPort(config.port());

        Association as;
        try {
            as = ae.connect(remote, rq);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            shutdown(executor, scheduler);
            throw new IOException("Connexion au PACS interrompue", e);
        } catch (IncompatibleConnectionException | GeneralSecurityException | IOException e) {
            shutdown(executor, scheduler);
            throw new IOException("PACS " + config.calledAet() + " injoignable ou association refusée : "
                    + e.getMessage(), e);
        }
        List<String> failures = new ArrayList<>();
        try {
            for (Validated v : instances) {
                int status = store(as, v);
                if (status != Status.Success && (status & 0xF000) != 0xB000) {
                    failures.add(String.format("statut %04X", status));
                }
            }
        } finally {
            try {
                as.release();
                as.waitForSocketClose();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "Fin d'association avec le PACS", e);
            }
            shutdown(executor, scheduler);
        }
        if (!failures.isEmpty()) {
            throw new IOException(failures.size() + " instance(s) refusée(s) par le PACS (" + failures.getFirst()
                    + (failures.size() > 1 ? ", …" : "") + ")");
        }
    }

    private static int store(Association as, Validated v) throws IOException {
        if (as.getTransferSyntaxesFor(v.sopClassUid()).stream().noneMatch(v.transferSyntaxUid()::equals)) {
            return Status.SOPclassNotSupported;
        }
        try (InputStream in = Files.newInputStream(v.instance().file())) {
            in.skipNBytes(v.datasetOffset());
            DimseRSP rsp = as.cstore(v.sopClassUid(), v.instance().sopInstanceUid(), Priority.NORMAL,
                    new InputStreamDataWriter(in), v.transferSyntaxUid());
            rsp.next();
            return rsp.getCommand().getInt(Tag.Status, -1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Envoi au PACS interrompu", e);
        }
    }

    private static void shutdown(ExecutorService executor, ScheduledExecutorService scheduler) {
        executor.shutdownNow();
        scheduler.shutdownNow();
    }
}
