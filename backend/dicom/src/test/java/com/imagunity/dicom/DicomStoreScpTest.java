package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests de bout en bout : un vrai client DICOM pousse vers le SCP de la box. */
class DicomStoreScpTest {

    private static final String STUDY = "2.25.1001";
    private static final String SERIES_1 = "2.25.1001.1";
    private static final String SERIES_2 = "2.25.1001.2";

    @TempDir
    Path storage;

    private int port;
    private DicomInputConfig config;
    private final BlockingQueue<ReceivedStudy> studies = new LinkedBlockingQueue<>();
    private DicomStoreScp scp;
    private TestScu pacs;

    @BeforeEach
    void setUp() throws IOException {
        port = freePort();
        config = new DicomInputConfig("127.0.0.1", port, Set.of("ORTHANC_A"), Map.of("IU_TO_B", "box-b"),
                storage, Duration.ofMillis(400), 0);
        scp = new DicomStoreScp(config, studies::add);
        TestPorts.start(scp);
        pacs = new TestScu("ORTHANC_A", port);
    }

    @AfterEach
    void tearDown() {
        pacs.close();
        scp.stop();
    }

    @Test
    void answersEcho() throws Exception {
        assertThat(pacs.echo("IU_TO_B")).isEqualTo(Status.Success);
    }

    @Test
    void receivesStudyAndSignalsItWhenComplete() throws Exception {
        List<Attributes> instances = List.of(
                TestDatasets.ct(STUDY, SERIES_1, "2.25.1001.1.1"),
                TestDatasets.ct(STUDY, SERIES_1, "2.25.1001.1.2"),
                TestDatasets.ct(STUDY, SERIES_2, "2.25.1001.2.1"));

        assertThat(pacs.store("IU_TO_B", instances)).containsOnly(Status.Success);

        ReceivedStudy study = studies.poll(5, TimeUnit.SECONDS);
        assertThat(study).isNotNull();
        assertThat(study.studyInstanceUid()).isEqualTo(STUDY);
        assertThat(study.calledAet()).isEqualTo("IU_TO_B");
        assertThat(study.destination()).isEqualTo("box-b");
        assertThat(study.callingAets()).containsExactly("ORTHANC_A");
        assertThat(study.instances()).hasSize(3);
        assertThat(study.seriesCount()).isEqualTo(2);

        // Les fichiers sont des DICOM Part 10 complets, identifiants conservés.
        for (ReceivedInstance instance : study.instances()) {
            assertThat(instance.file()).exists().startsWith(storage.resolve("IU_TO_B").resolve("box-b").resolve(STUDY));
            try (DicomInputStream in = new DicomInputStream(instance.file().toFile())) {
                Attributes fmi = in.readFileMetaInformation();
                Attributes ds = in.readDataset();
                assertThat(fmi.getString(Tag.MediaStorageSOPInstanceUID)).isEqualTo(instance.sopInstanceUid());
                assertThat(ds.getString(Tag.SOPInstanceUID)).isEqualTo(instance.sopInstanceUid());
                assertThat(ds.getString(Tag.StudyInstanceUID)).isEqualTo(STUDY);
                assertThat(ds.getBytes(Tag.PixelData)).hasSize(16 * 16 * 2);
            }
        }
        assertThat(studies.poll(1, TimeUnit.SECONDS)).as("une seule notification").isNull();
    }

    @Test
    void rejectsUnknownCallingAet() {
        try (TestScu intruder = new TestScu("INCONNU", port)) {
            assertThatThrownBy(() -> intruder.echo("IU_TO_B"))
                    .isInstanceOf(AAssociateRJ.class)
                    .satisfies(e -> assertThat(((AAssociateRJ) e).getReason())
                            .isEqualTo(AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED));
        }
    }

    @Test
    void rejectsUnknownDestination() {
        assertThatThrownBy(() -> pacs.echo("IU_TO_Z"))
                .isInstanceOf(AAssociateRJ.class)
                .satisfies(e -> assertThat(((AAssociateRJ) e).getReason())
                        .isEqualTo(AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED));
    }

    @Test
    void rejectsInstanceWhoseUidDoesNotMatchTheCommand() throws Exception {
        int status = pacs.storeWithCommandUid("IU_TO_B", TestDatasets.ct(STUDY, SERIES_1, "2.25.1001.1.1"),
                "2.25.9999");

        assertThat(status).isEqualTo(Status.CannotUnderstand);
        assertThat(studies.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(Files.list(storage.resolve(".tmp"))).isEmpty();
    }

    @Test
    void rejectsForgedStudyUid() throws Exception {
        List<Integer> statuses = pacs.store("IU_TO_B",
                List.of(TestDatasets.ct("../../evil", SERIES_1, "2.25.1001.1.1")));

        assertThat(statuses).containsExactly(Status.CannotUnderstand);
        assertThat(storage.resolve("IU_TO_B")).doesNotExist();
    }

    @Test
    void refusesInstancesWhenDiskSpaceIsLow() throws Exception {
        scp.stop();
        var tightConfig = new DicomInputConfig("127.0.0.1", port, Set.of("ORTHANC_A"), Map.of("IU_TO_B", "box-b"),
                storage, Duration.ofMillis(400), Long.MAX_VALUE);
        scp = new DicomStoreScp(tightConfig, studies::add);
        TestPorts.start(scp);

        List<Integer> statuses = pacs.store("IU_TO_B", List.of(TestDatasets.ct(STUDY, SERIES_1, "2.25.1001.1.1")));

        assertThat(statuses).containsExactly(Status.OutOfResources);
    }

    @Test
    void resumesPendingStudyAfterRestart() throws Exception {
        pacs.store("IU_TO_B", List.of(
                TestDatasets.ct(STUDY, SERIES_1, "2.25.1001.1.1"),
                TestDatasets.ct(STUDY, SERIES_2, "2.25.1001.2.1")));
        scp.stop(); // arrêt avant la fin du délai d'inactivité
        assertThat(studies).isEmpty();

        scp = new DicomStoreScp(config, studies::add);
        TestPorts.start(scp);

        ReceivedStudy study = studies.poll(5, TimeUnit.SECONDS);
        assertThat(study).isNotNull();
        assertThat(study.instances()).hasSize(2);
        assertThat(study.callingAets()).as("inconnu après reprise").isEmpty();
        assertThat(study.instances()).allSatisfy(i -> {
            assertThat(i.sopClassUid()).isEqualTo(org.dcm4che3.data.UID.CTImageStorage);
            assertThat(i.size()).isPositive();
        });
    }

    @Test
    void rejectsInvalidCommandUid() throws Exception {
        int status = pacs.storeWithCommandUid("IU_TO_B", TestDatasets.ct(STUDY, SERIES_1, "2.25.1001.1.1"), "abc");

        assertThat(status).isEqualTo(Status.CannotUnderstand);
    }

    @Test
    void rejectsForgedSeriesUid() throws Exception {
        List<Integer> statuses = pacs.store("IU_TO_B", List.of(TestDatasets.ct(STUDY, "1..2", "2.25.1001.1.1")));

        assertThat(statuses).containsExactly(Status.CannotUnderstand);
    }

    @Test
    void reportsStudiesInProgress() throws Exception {
        scp.stop();
        var slowConfig = new DicomInputConfig("127.0.0.1", port, Set.of("ORTHANC_A"), Map.of("IU_TO_B", "box-b"),
                storage, Duration.ofMinutes(5), 0);
        scp = new DicomStoreScp(slowConfig, studies::add);
        TestPorts.start(scp);

        pacs.store("IU_TO_B", List.of(TestDatasets.ct(STUDY, SERIES_1, "2.25.1001.1.1")));

        assertThat(scp.pendingStudies()).isEqualTo(1);
        assertThat(scp.studyIdleTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void sameScpRestartsAfterStop() throws Exception {
        scp.stop();

        TestPorts.start(scp);

        assertThat(pacs.echo("IU_TO_B")).isEqualTo(Status.Success);
    }

    @Test
    void failedStartCanBeRetried() throws Exception {
        try (java.net.ServerSocket taken = new java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            var busy = new DicomStoreScp(new DicomInputConfig("127.0.0.1", taken.getLocalPort(), Set.of("ORTHANC_A"),
                    Map.of("IU_TO_B", "box-b"), storage.resolve("busy"), Duration.ofMillis(400), 0), studies::add);

            org.assertj.core.api.Assertions.assertThatThrownBy(busy::start).isInstanceOf(IOException.class);
            busy.stop(); // déjà libéré par l'échec : sans effet
        }
    }

    @Test
    void startAndStopAreIdempotent() throws Exception {
        TestPorts.start(scp);
        assertThat(pacs.echo("IU_TO_B")).isEqualTo(Status.Success);

        scp.close();
        scp.stop();
    }

    @Test
    void listensOnAllInterfacesWithoutBindAddress() throws Exception {
        scp.stop();
        var anyConfig = new DicomInputConfig(" ", port, Set.of("ORTHANC_A"), Map.of("IU_TO_B", "box-b"),
                storage, Duration.ofMillis(400), 0);
        scp = new DicomStoreScp(anyConfig, studies::add);
        TestPorts.start(scp);

        assertThat(pacs.echo("IU_TO_B")).isEqualTo(Status.Success);
    }

    @Test
    void ignoresPendingInstancesOfRemovedDestination() throws Exception {
        pacs.store("IU_TO_B", List.of(TestDatasets.ct(STUDY, SERIES_1, "2.25.1001.1.1")));
        scp.stop();
        assertThat(studies).isEmpty();

        var otherRoutes = new DicomInputConfig("127.0.0.1", port, Set.of("ORTHANC_A"), Map.of("IU_TO_C", "box-c"),
                storage, Duration.ofMillis(400), 0);
        scp = new DicomStoreScp(otherRoutes, studies::add);
        TestPorts.start(scp);

        assertThat(studies.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(storage.resolve("IU_TO_B").resolve("box-b").resolve(STUDY)).as("fichiers conservés").exists();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
