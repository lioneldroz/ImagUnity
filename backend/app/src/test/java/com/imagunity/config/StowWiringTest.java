package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Dépôt STOW-RS par HTTP réel, jusqu'au dossier de réception de l'entrée DICOM (données synthétiques). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StowWiringTest {

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        int dicomPort = port;
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:stow-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        registry.add("imagunity.dicom.enabled", () -> "true");
        registry.add("imagunity.dicom.bind-address", () -> "127.0.0.1");
        registry.add("imagunity.dicom.port", () -> dicomPort);
        registry.add("imagunity.dicom.storage-dir", () -> storage.toString());
        registry.add("imagunity.dicom.min-free-space", () -> "1MB");
        registry.add("imagunity.dicom.study-idle-timeout", () -> "1h");
        registry.add("imagunity.dicom.routes[0].called-aet", () -> "IU_TO_B");
        registry.add("imagunity.dicom.routes[0].destination", () -> "box-b");
        registry.add("imagunity.dicomweb.password", () -> "pacs-de-test");
    }

    @Autowired
    private Environment environment;

    private static byte[] instance() throws Exception {
        Attributes attrs = new Attributes();
        attrs.setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage);
        attrs.setString(Tag.SOPInstanceUID, VR.UI, "2.25.90.1.1");
        attrs.setString(Tag.StudyInstanceUID, VR.UI, "2.25.90");
        attrs.setString(Tag.SeriesInstanceUID, VR.UI, "2.25.90.1");
        attrs.setString(Tag.PatientName, VR.PN, "Synthetique^Stow");
        attrs.setString(Tag.Modality, VR.CS, "OT");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DicomOutputStream out = new DicomOutputStream(bytes, UID.ExplicitVRLittleEndian)) {
            out.writeDataset(attrs.createFileMetaInformation(UID.ExplicitVRLittleEndian), attrs);
        }
        return bytes.toByteArray();
    }

    @Test
    void pacsDepositsAnInstanceOverHttp() throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes("--limite\r\nContent-Type: application/dicom\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        body.writeBytes(instance());
        body.writeBytes("\r\n--limite--\r\n".getBytes(StandardCharsets.US_ASCII));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getProperty("local.server.port") + "/dicomweb/IU_TO_B/studies"))
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        "pacs:pacs-de-test".getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "multipart/related; type=\"application/dicom\"; boundary=limite")
                .header("Accept", "application/dicom+json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/dicom+json");
        assertThat(response.body()).contains("\"00081199\"").contains("2.25.90.1.1");
        assertThat(Files.exists(storage.resolve("IU_TO_B/box-b/2.25.90/2.25.90.1/2.25.90.1.1.dcm"))).isTrue();
    }
}
