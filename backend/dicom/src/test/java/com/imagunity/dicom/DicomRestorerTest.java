package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import com.imagunity.domain.exam.StudyRestorer;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Restitution vers un « PACS » de test : le SCP de la box elle-même, qui range ce qu'il reçoit.
 */
class DicomRestorerTest {

    private static final String STUDY = "2.25.5005";

    @TempDir
    Path dir;

    private int port;
    private DicomStoreScp pacs;
    private final BlockingQueue<ReceivedStudy> received = new LinkedBlockingQueue<>();
    private DicomRestorer restorer;

    @BeforeEach
    void setUp() throws IOException {
        port = freePort();
        pacs = pacs(0);
        restorer = new DicomRestorer(new RestoreConfig("127.0.0.1", port, "ORTHANC_B", "IMAGUNITY_B"));
    }

    private DicomStoreScp pacs(long minFreeSpace) throws IOException {
        DicomStoreScp scp = new DicomStoreScp(new DicomInputConfig("127.0.0.1", port, Set.of("IMAGUNITY_B"),
                Map.of("ORTHANC_B", "pacs"), dir.resolve("pacs"), Duration.ofMillis(300), minFreeSpace), received::add);
        scp.start();
        return scp;
    }

    @AfterEach
    void tearDown() {
        pacs.stop();
    }

    /** Fichier Part 10, comme ceux extraits d'un paquet. */
    private StudyRestorer.Instance instance(String series, String sop) throws IOException {
        Attributes ds = TestDatasets.ct(STUDY, series, sop);
        Path file = Files.createDirectories(dir.resolve("restore").resolve(series)).resolve(sop + ".dcm");
        try (DicomOutputStream out = new DicomOutputStream(file.toFile())) {
            out.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds);
        }
        return new StudyRestorer.Instance(series, sop, file);
    }

    @Test
    void restoresEveryInstanceUnchanged() throws Exception {
        List<StudyRestorer.Instance> instances = List.of(
                instance(STUDY + ".1", STUDY + ".1.1"), instance(STUDY + ".1", STUDY + ".1.2"),
                instance(STUDY + ".2", STUDY + ".2.1"));

        restorer.restore(STUDY, instances);

        ReceivedStudy study = received.poll(5, TimeUnit.SECONDS);
        assertThat(study).isNotNull();
        assertThat(study.studyInstanceUid()).isEqualTo(STUDY);
        assertThat(study.callingAets()).containsExactly("IMAGUNITY_B");
        assertThat(study.instances()).hasSize(3);
        for (ReceivedInstance arrived : study.instances()) {
            Path original = instances.stream().filter(i -> i.sopInstanceUid().equals(arrived.sopInstanceUid()))
                    .findFirst().orElseThrow().file();
            assertThat(dataset(arrived.file())).isEqualTo(dataset(original));
        }
    }

    @Test
    void refusesEverythingIfOneInstanceIsInvalid() throws Exception {
        List<StudyRestorer.Instance> instances = new ArrayList<>(List.of(instance(STUDY + ".1", STUDY + ".1.1")));
        StudyRestorer.Instance good = instance(STUDY + ".1", STUDY + ".1.2");
        instances.add(new StudyRestorer.Instance(good.seriesInstanceUid(), "2.25.999", good.file()));

        assertThatThrownBy(() -> restorer.restore(STUDY, instances))
                .isInstanceOf(DicomValidationException.class)
                .hasMessageContaining("SOP Instance UID différent du manifeste");
        assertThat(received.poll(1, TimeUnit.SECONDS)).as("rien n'est envoyé").isNull();
    }

    @Test
    void rejectsWrongStudyOrSeries() throws Exception {
        StudyRestorer.Instance ok = instance(STUDY + ".1", STUDY + ".1.1");

        assertThatThrownBy(() -> restorer.restore("2.25.1", List.of(ok)))
                .hasMessageContaining("Study Instance UID");
        assertThatThrownBy(() -> restorer.restore(STUDY,
                List.of(new StudyRestorer.Instance("2.25.1.9", ok.sopInstanceUid(), ok.file()))))
                .hasMessageContaining("Series Instance UID");
    }

    @Test
    void rejectsInconsistentFile() throws Exception {
        // Méta-informations et jeu de données qui ne désignent pas la même instance ou la même classe.
        Attributes ds = TestDatasets.ct(STUDY, STUDY + ".1", STUDY + ".1.1");
        Path otherSop = dir.resolve("autre-sop.dcm");
        try (DicomOutputStream out = new DicomOutputStream(otherSop.toFile())) {
            Attributes fmi = ds.createFileMetaInformation(UID.ExplicitVRLittleEndian);
            fmi.setString(Tag.MediaStorageSOPInstanceUID, org.dcm4che3.data.VR.UI, STUDY + ".1.9");
            out.writeDataset(fmi, ds);
        }
        Path otherClass = dir.resolve("autre-classe.dcm");
        try (DicomOutputStream out = new DicomOutputStream(otherClass.toFile())) {
            Attributes fmi = ds.createFileMetaInformation(UID.ExplicitVRLittleEndian);
            fmi.setString(Tag.MediaStorageSOPClassUID, org.dcm4che3.data.VR.UI, UID.MRImageStorage);
            out.writeDataset(fmi, ds);
        }

        assertThatThrownBy(() -> restorer.restore(STUDY,
                List.of(new StudyRestorer.Instance(STUDY + ".1", STUDY + ".1.9", otherSop))))
                .hasMessageContaining("incohérent dans le fichier");
        assertThatThrownBy(() -> restorer.restore(STUDY,
                List.of(new StudyRestorer.Instance(STUDY + ".1", STUDY + ".1.1", otherClass))))
                .hasMessageContaining("SOP Class UID incohérent");
    }

    @Test
    void rejectsMalformedFiles() throws Exception {
        Path garbage = Files.write(dir.resolve("garbage.dcm"), new byte[] {1, 2, 3, 4});
        Path truncated = dir.resolve("tronque.dcm");
        byte[] full = Files.readAllBytes(instance(STUDY + ".1", STUDY + ".1.1").file());
        Files.write(truncated, java.util.Arrays.copyOf(full, full.length - 100));
        Path noMeta = dir.resolve("sans-meta.dcm");
        try (DicomOutputStream out = new DicomOutputStream(Files.newOutputStream(noMeta), UID.ExplicitVRLittleEndian)) {
            out.writeDataset(null, TestDatasets.ct(STUDY, STUDY + ".1", STUDY + ".1.3"));
        }

        assertThatThrownBy(() -> restorer.restore(STUDY,
                List.of(new StudyRestorer.Instance(STUDY + ".1", STUDY + ".1.1", garbage))))
                .isInstanceOf(DicomValidationException.class);
        assertThatThrownBy(() -> restorer.restore(STUDY,
                List.of(new StudyRestorer.Instance(STUDY + ".1", STUDY + ".1.1", truncated))))
                .isInstanceOf(DicomValidationException.class)
                .hasMessageContaining("illisible");
        assertThatThrownBy(() -> restorer.restore(STUDY,
                List.of(new StudyRestorer.Instance(STUDY + ".1", STUDY + ".1.3", noMeta))))
                .isInstanceOf(DicomValidationException.class);
    }

    @Test
    void rejectsEmptyStudy() {
        assertThatThrownBy(() -> restorer.restore(STUDY, List.of())).isInstanceOf(DicomValidationException.class);
    }

    @Test
    void reportsPacsRefusal() throws Exception {
        StudyRestorer.Instance instance = instance(STUDY + ".1", STUDY + ".1.1");
        DicomRestorer wrongAet = new DicomRestorer(new RestoreConfig("127.0.0.1", port, "INCONNU", "IMAGUNITY_B"));

        assertThatThrownBy(() -> wrongAet.restore(STUDY, List.of(instance)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("association refusée");
    }

    @Test
    void reportsUnreachablePacs() throws Exception {
        StudyRestorer.Instance instance = instance(STUDY + ".1", STUDY + ".1.1");
        DicomRestorer nobody = new DicomRestorer(new RestoreConfig("127.0.0.1", freePort(), "ORTHANC_B", "IMAGUNITY_B"));

        assertThatThrownBy(() -> nobody.restore(STUDY, List.of(instance)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("injoignable");
    }

    @Test
    void reportsInstancesRejectedByThePacs() throws Exception {
        pacs.stop();
        pacs = pacs(Long.MAX_VALUE); // « disque plein » : le PACS refuse chaque instance
        List<StudyRestorer.Instance> instances = List.of(instance(STUDY + ".1", STUDY + ".1.1"),
                instance(STUDY + ".1", STUDY + ".1.2"));

        assertThatThrownBy(() -> restorer.restore(STUDY, instances))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("2 instance(s) refusée(s)")
                .hasMessageContaining("A700");
    }

    @Test
    void refusesMoreSopClassesThanOneAssociationCanCarry() throws Exception {
        List<StudyRestorer.Instance> instances = new ArrayList<>();
        for (int i = 1; i <= 128; i++) {
            String sop = STUDY + ".1." + i;
            Attributes ds = TestDatasets.ct(STUDY, STUDY + ".1", sop);
            ds.setString(Tag.SOPClassUID, org.dcm4che3.data.VR.UI, "1.2.826.0.1.3680043.10.99." + i);
            Path file = Files.createDirectories(dir.resolve("classes")).resolve(sop + ".dcm");
            try (DicomOutputStream out = new DicomOutputStream(file.toFile())) {
                out.writeDataset(ds.createFileMetaInformation(UID.ExplicitVRLittleEndian), ds);
            }
            instances.add(new StudyRestorer.Instance(STUDY + ".1", sop, file));
        }

        assertThatThrownBy(() -> restorer.restore(STUDY, instances))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("Trop de combinaisons");
    }

    @Test
    void interruptionStopsTheRestore() throws Exception {
        StudyRestorer.Instance instance = instance(STUDY + ".1", STUDY + ".1.1");
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> restorer.restore(STUDY, List.of(instance)))
                    .isInstanceOf(IOException.class);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void configValidation() {
        assertThatThrownBy(() -> new RestoreConfig(null, 104, "A", "B")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RestoreConfig("h", 0, "A", "B")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestoreConfig("h", 70000, "A", "B")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestoreConfig("h", 104, " ", "B")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestoreConfig("h", 104, "A", "X".repeat(17)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RestoreConfig("h", 104, null, "B")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DicomRestorer(null)).isInstanceOf(NullPointerException.class);
    }

    private static Attributes dataset(Path file) throws IOException {
        try (DicomInputStream in = new DicomInputStream(file.toFile())) {
            in.readFileMetaInformation();
            return in.readDataset();
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
