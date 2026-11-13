package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.rules.RoutingRule;
import com.imagunity.domain.exam.ReceivedStudy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
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
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** STOW-RS : instances déposées par HTTP, même traitement que le C-STORE (contrôles, routage, étude). */
class StowServiceTest {

    private static final String TYPE = "multipart/related; type=\"application/dicom\"; boundary=" + "frontiere-42";

    @TempDir
    Path storage;

    private final BlockingQueue<ReceivedStudy> studies = new LinkedBlockingQueue<>();
    private DicomStoreScp scp;
    private StowService stow;

    @BeforeEach
    void setUp() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        DicomInputConfig config = new DicomInputConfig("127.0.0.1", port, Set.of("ORTHANC_A"),
                Map.of("IU_TO_B", "box-b"), storage, Duration.ofMillis(400), 0, "IU_AUTO",
                List.of(new RoutingRule("irm", Map.of("Modality", "MR"), List.of("box-b", "box-c"))));
        scp = new DicomStoreScp(config, studies::add);
        TestPorts.start(scp);
        stow = new StowService(scp);
    }

    @AfterEach
    void tearDown() {
        scp.stop();
    }

    static byte[] part10(Attributes dataset) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DicomOutputStream out = new DicomOutputStream(bytes, UID.ExplicitVRLittleEndian)) {
            out.writeDataset(dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian), dataset);
        }
        return bytes.toByteArray();
    }

    private static Attributes image(String study, String sop, String modality) {
        Attributes attrs = TestDatasets.ct(study, study + ".1", sop);
        attrs.setString(Tag.Modality, VR.CS, modality);
        return attrs;
    }

    private StowService.Response post(String aet, String studyUid, List<byte[]> parts) throws IOException {
        return stow.store("dicomweb:pacs", aet, studyUid, TYPE, new ByteArrayInputStream(
                MultipartRelatedReaderTest.body("", parts, "application/dicom")));
    }

    @Test
    void instancesAreStoredAndTheStudyIsHandedOver() throws Exception {
        StowService.Response response = post("IU_TO_B", null, List.of(
                part10(image("2.25.80", "2.25.80.1.1", "CT")), part10(image("2.25.80", "2.25.80.1.2", "CT"))));

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.contentType()).isEqualTo("application/dicom+json");
        assertThat(response.body()).startsWith("{\"00081199\":{\"vr\":\"SQ\",\"Value\":[")
                .contains("\"00081155\":{\"vr\":\"UI\",\"Value\":[\"2.25.80.1.2\"]}")
                .contains("\"00081150\":{\"vr\":\"UI\",\"Value\":[\"" + UID.CTImageStorage + "\"]}")
                .doesNotContain("00081198");
        ReceivedStudy study = studies.poll(5, TimeUnit.SECONDS);
        assertThat(study).isNotNull();
        assertThat(study.destination()).isEqualTo("box-b");
        assertThat(study.callingAets()).containsExactly("dicomweb:pacs");
        assertThat(study.instances()).hasSize(2);
    }

    @Test
    void routingRulesApplyToStowToo() throws Exception {
        assertThat(post("IU_AUTO", null, List.of(part10(image("2.25.81", "2.25.81.1.1", "MR")))).status())
                .isEqualTo(200);

        List<ReceivedStudy> received = new ArrayList<>(List.of(studies.poll(5, TimeUnit.SECONDS),
                studies.poll(5, TimeUnit.SECONDS)));
        received.sort(Comparator.comparing(ReceivedStudy::destination));
        assertThat(received).extracting(ReceivedStudy::destination).containsExactly("box-b", "box-c");
    }

    @Test
    void partialFailuresAreReportedPerInstance() throws Exception {
        Attributes otherStudy = image("2.25.99", "2.25.99.1.1", "CT");
        byte[] notDicom = "pas un fichier DICOM".getBytes(StandardCharsets.UTF_8);
        byte[] noRule = part10(image("2.25.82", "2.25.82.1.1", "CT"));

        StowService.Response mixed = post("IU_TO_B", "2.25.82", List.of(
                part10(image("2.25.82", "2.25.82.1.2", "CT")), part10(otherStudy), notDicom));
        assertThat(mixed.status()).isEqualTo(202);
        assertThat(mixed.body()).startsWith("{\"00081198\"")
                .contains("\"00081155\":{\"vr\":\"UI\",\"Value\":[\"2.25.99.1.1\"]},\"00081197\":{\"vr\":\"US\",\"Value\":["
                        + DicomStoreScp.STUDY_MISMATCH + "]}")
                .contains("{\"00081197\":{\"vr\":\"US\",\"Value\":[" + Status.CannotUnderstand + "]}")
                .contains("\"00081199\"");

        StowService.Response refused = post("IU_AUTO", null, List.of(noRule));
        assertThat(refused.status()).as("aucune règle : rien d'accepté").isEqualTo(409);
        assertThat(refused.body()).contains("\"00081197\":{\"vr\":\"US\",\"Value\":[" + Status.ProcessingFailure + "]}")
                .doesNotContain("00081199");
        assertThat(post("IU_INCONNU", null, List.of(noRule)).status()).isEqualTo(409);
        try (var files = Files.walk(storage)) {
            assertThat(files.filter(p -> p.toString().endsWith(".part"))).as("aucun reste temporaire").isEmpty();
        }
    }

    @Test
    void requestsThatAreNotStowAreRejected() throws IOException {
        byte[] ok = part10(image("2.25.83", "2.25.83.1.1", "CT"));
        assertThat(stow.store("p", "IU_TO_B", null, "application/dicom", new ByteArrayInputStream(ok)).status())
                .isEqualTo(415);
        assertThat(stow.store("p", "IU_TO_B", null, null, new ByteArrayInputStream(ok)).status()).isEqualTo(415);
        assertThat(stow.store("p", "IU_TO_B", null, "multipart/related; type=\"application/dicom+json\"; boundary=x",
                new ByteArrayInputStream(ok)).status()).isEqualTo(415);
        assertThat(stow.store("p", "IU_TO_B", null, "multipart/related; type=\"application/dicom\"",
                new ByteArrayInputStream(ok)).status()).isEqualTo(400);
        assertThat(stow.store("p", "IU_TO_B", null, "multipart/related; boundary=" + "x".repeat(250),
                new ByteArrayInputStream(ok)).status()).isEqualTo(400);
        assertThat(stow.store("p", "IU_TO_B", "pas-un-uid", TYPE, new ByteArrayInputStream(ok)).status())
                .isEqualTo(400);
        assertThat(stow.store("p", "IU_TO_B", null, TYPE, new ByteArrayInputStream("vide".getBytes())).status())
                .as("corps illisible").isEqualTo(400);
        assertThat(stow.store("p", "IU_TO_B", null, TYPE, new ByteArrayInputStream(
                ("--frontiere-42--").getBytes(StandardCharsets.UTF_8))).status()).as("aucune instance").isEqualTo(400);
        byte[] truncated = MultipartRelatedReaderTest.body("", List.of(ok), "application/dicom");
        truncated = java.util.Arrays.copyOf(truncated, truncated.length - 20);
        byte[] twoParts = new byte[0];
        StowService.Response cut = stow.store("p", "IU_TO_B", null, TYPE, new ByteArrayInputStream(
                concat(MultipartRelatedReaderTest.body("", List.of(ok), "application/dicom"), truncated, twoParts)));
        assertThat(cut.status()).isIn(200, 202);
        byte[] otherType = MultipartRelatedReaderTest.body("", List.of(ok), "text/plain");
        assertThat(stow.store("p", "IU_TO_B", null, TYPE, new ByteArrayInputStream(otherType)).body())
                .contains("00081198");
    }

    @Test
    void part10IsRequiredWithValidMetadataAndFreeSpace() throws IOException {
        Attributes dataset = image("2.25.84", "2.25.84.1.1", "CT");
        ByteArrayOutputStream noMeta = new ByteArrayOutputStream();
        try (DicomOutputStream out = new DicomOutputStream(noMeta, UID.ExplicitVRLittleEndian)) {
            out.writeDataset(null, dataset);
        }
        Attributes badMeta = dataset.createFileMetaInformation(UID.ExplicitVRLittleEndian);
        badMeta.setString(Tag.MediaStorageSOPInstanceUID, VR.UI, "pas-un-uid");
        ByteArrayOutputStream badUids = new ByteArrayOutputStream();
        try (DicomOutputStream out = new DicomOutputStream(badUids, UID.ExplicitVRLittleEndian)) {
            out.writeDataset(badMeta, dataset);
        }

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scp.storePart10("p", "IU_TO_B",
                new ByteArrayInputStream(noMeta.toByteArray()), null)).hasMessageContaining("Part 10");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scp.storePart10("p", "IU_TO_B",
                new ByteArrayInputStream(badUids.toByteArray()), null)).hasMessageContaining("UID des méta");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> scp.storePart10("p", null,
                new ByteArrayInputStream(part10(dataset)), null)).hasMessageContaining("Destination inconnue");

        DicomStoreScp full = new DicomStoreScp(new DicomInputConfig("127.0.0.1", 1, Set.of(), Map.of("IU_TO_B", "box-b"),
                storage.resolve("plein"), Duration.ofSeconds(1), Long.MAX_VALUE), s -> { });
        Files.createDirectories(storage.resolve("plein"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> full.storePart10("p", "IU_TO_B",
                new ByteArrayInputStream(part10(dataset)), null))
                .isInstanceOfSatisfying(DicomStoreScp.Refused.class,
                        e -> assertThat(e.getStatus()).isEqualTo(Status.OutOfResources));

        DicomStoreScp small = new DicomStoreScp(new DicomInputConfig("127.0.0.1", 1, Set.of(), Map.of("IU_TO_B",
                "box-b"), storage.resolve("petit"), Duration.ofSeconds(1), 0), s -> { });
        small.limits(new DicomStoreScp.Limits(100, 1, Duration.ofSeconds(1), Duration.ofSeconds(1), Set.of()));
        Files.createDirectories(storage.resolve("petit/.tmp"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> small.storePart10("p", "IU_TO_B",
                new ByteArrayInputStream(part10(dataset)), null)).hasMessageContaining("trop grande");
    }

    private static byte[] concat(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) {
            out.writeBytes(a);
        }
        return out.toByteArray();
    }
}
