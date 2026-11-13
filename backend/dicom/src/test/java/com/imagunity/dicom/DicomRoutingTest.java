package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.domain.exam.ReceivedStudy;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.net.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Routage par champ DICOM, de bout en bout : un vrai client DICOM pousse vers la destination de routage. */
class DicomRoutingTest {

    @TempDir
    Path storage;

    private int port;
    private DicomInputConfig config;
    private final BlockingQueue<ReceivedStudy> studies = new LinkedBlockingQueue<>();
    private DicomStoreScp scp;
    private TestScu pacs;

    @BeforeEach
    void setUp() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        config = new DicomInputConfig("127.0.0.1", port, Set.of("ORTHANC_A"), Map.of("IU_TO_B", "box-b"), storage,
                Duration.ofMillis(400), 0, "IU_AUTO", List.of(
                        new RoutingRule("scanner-chu", Map.of("Modality", "CT", "InstitutionName", "CHU*"),
                                List.of("box-b", "box-c")),
                        new RoutingRule("irm", Map.of("Modality", "MR"), List.of("box-d"))));
        scp = new DicomStoreScp(config, studies::add);
        TestPorts.start(scp);
        pacs = new TestScu("ORTHANC_A", port);
    }

    @AfterEach
    void tearDown() {
        pacs.close();
        scp.stop();
    }

    private static Attributes image(String study, String series, String sop, String modality, String institution) {
        Attributes attrs = TestDatasets.ct(study, series, sop);
        attrs.setString(Tag.Modality, VR.CS, modality);
        attrs.setString(Tag.InstitutionName, VR.LO, institution);
        return attrs;
    }

    private List<ReceivedStudy> await(int count) throws InterruptedException {
        List<ReceivedStudy> received = new ArrayList<>();
        while (received.size() < count) {
            ReceivedStudy study = studies.poll(5, TimeUnit.SECONDS);
            assertThat(study).as("étude attendue").isNotNull();
            received.add(study);
        }
        received.sort(Comparator.comparing(ReceivedStudy::destination));
        return received;
    }

    @Test
    void centralRulesReplaceTheLocalOnesWithoutRestart() throws Exception {
        java.util.concurrent.atomic.AtomicReference<java.util.Optional<List<RoutingRule>>> central =
                new java.util.concurrent.atomic.AtomicReference<>(java.util.Optional.of(List.of(
                        new RoutingRule("central-ct", Map.of("Modality", "CT"), List.of("box-z")))));
        scp.centralRouting(central::get);
        assertThat(pacs.store("IU_AUTO", List.of(image("1.2.9.1", "1.2.9.1.1", "1.2.9.1.1.1", "CT",
                "CHU NORD")))).containsExactly(Status.Success);
        assertThat(await(1)).extracting(ReceivedStudy::destination).containsExactly("box-z");
        assertThat(pacs.store("IU_AUTO", List.of(image("1.2.9.2", "1.2.9.2.1", "1.2.9.2.1.1", "MR",
                "CHU NORD")))).singleElement()
                .as("règle locale « irm » remplacée : aucune règle centrale ne correspond")
                .isNotEqualTo(Status.Success);
        central.set(java.util.Optional.empty());
        assertThat(pacs.store("IU_AUTO", List.of(image("1.2.9.3", "1.2.9.3.1", "1.2.9.3.1.1", "MR",
                "CHU NORD")))).containsExactly(Status.Success);
        assertThat(await(1)).extracting(ReceivedStudy::destination).containsExactly("box-d");
    }

    @Test
    void studyIsRoutedByItsFieldsToEveryPartnerOfTheFirstMatchingRule() throws Exception {
        assertThat(pacs.echo("IU_AUTO")).isEqualTo(Status.Success);
        assertThat(pacs.store("IU_AUTO", List.of(
                image("2.25.70", "2.25.70.1", "2.25.70.1.1", "CT", "CHU Exemple"),
                image("2.25.70", "2.25.70.1", "2.25.70.1.2", "CT", "CHU Exemple")))).containsOnly(Status.Success);

        List<ReceivedStudy> received = await(2);

        assertThat(received).extracting(ReceivedStudy::destination).containsExactly("box-b", "box-c");
        assertThat(received).allSatisfy(study -> {
            assertThat(study.calledAet()).isEqualTo("IU_AUTO");
            assertThat(study.instances()).hasSize(2);
            assertThat(study.instances()).allSatisfy(i -> assertThat(i.file())
                    .startsWith(storage.resolve("IU_AUTO").resolve(study.destination())).exists());
        });
        // Chaque partenaire a ses propres fichiers : supprimer ceux de l'un ne touche pas l'autre.
        Files.delete(received.getFirst().instances().getFirst().file());
        assertThat(received.get(1).instances().getFirst().file()).exists();
    }

    @Test
    void otherRuleAndFixedRoutesStillApply() throws Exception {
        pacs.store("IU_AUTO", List.of(image("2.25.71", "2.25.71.1", "2.25.71.1.1", "MR", "Clinique")));
        pacs.store("IU_TO_B", List.of(image("2.25.72", "2.25.72.1", "2.25.72.1.1", "MR", "Clinique")));

        List<ReceivedStudy> received = await(2);

        assertThat(received).extracting(ReceivedStudy::destination, ReceivedStudy::calledAet)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("box-b", "IU_TO_B"),
                        org.assertj.core.groups.Tuple.tuple("box-d", "IU_AUTO"));
    }

    @Test
    void instanceWithoutMatchingRuleIsRefusedToThePacs() throws Exception {
        List<Integer> statuses = pacs.store("IU_AUTO", List.of(
                image("2.25.73", "2.25.73.1", "2.25.73.1.1", "CT", "Clinique")));

        assertThat(statuses).containsExactly(Status.ProcessingFailure);
        assertThat(studies.poll(1, TimeUnit.SECONDS)).isNull();
        try (var files = Files.walk(storage)) {
            assertThat(files.filter(p -> p.toString().endsWith(".dcm") || p.toString().endsWith(".part"))).isEmpty();
        }
    }

    @Test
    void routedInstancesAreResumedForEachPartnerAfterRestart() throws Exception {
        pacs.store("IU_AUTO", List.of(image("2.25.74", "2.25.74.1", "2.25.74.1.1", "CT", "CHU Exemple")));
        scp.stop();
        assertThat(studies).isEmpty();

        scp = new DicomStoreScp(config, studies::add);
        TestPorts.start(scp);

        assertThat(await(2)).extracting(ReceivedStudy::destination).containsExactly("box-b", "box-c");
    }

    @Test
    void pendingInstancesOfARemovedRoutingDestinationAreKept() throws Exception {
        pacs.store("IU_AUTO", List.of(image("2.25.75", "2.25.75.1", "2.25.75.1.1", "MR", "Clinique")));
        scp.stop();

        scp = new DicomStoreScp(new DicomInputConfig("127.0.0.1", port, Set.of("ORTHANC_A"),
                Map.of("IU_TO_B", "box-b"), storage, Duration.ofMillis(400), 0), studies::add);
        TestPorts.start(scp);

        assertThat(studies.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(storage.resolve("IU_AUTO").resolve("box-d")).exists();
    }
}
