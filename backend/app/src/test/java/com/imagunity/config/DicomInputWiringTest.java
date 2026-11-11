package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.dicom.DicomInputConfig;
import com.imagunity.dicom.DicomStoreScp;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Vérifie la lecture de la configuration et l'ouverture du port DICOM au démarrage. */
@SpringBootTest
class DicomInputWiringTest {

    @TempDir
    static Path storage;

    private static final int PORT = freePort();

    @DynamicPropertySource
    static void dicomProperties(DynamicPropertyRegistry registry) {
        registry.add("imagunity.dicom.enabled", () -> "true");
        registry.add("imagunity.dicom.bind-address", () -> "127.0.0.1");
        registry.add("imagunity.dicom.port", () -> PORT);
        registry.add("imagunity.dicom.storage-dir", () -> storage.toString());
        registry.add("imagunity.dicom.study-idle-timeout", () -> "5s");
        registry.add("imagunity.dicom.min-free-space", () -> "10MB");
        registry.add("imagunity.dicom.allowed-calling-aets[0]", () -> "ORTHANC_A");
        registry.add("imagunity.dicom.routes[0].called-aet", () -> "IU_TO_B");
        registry.add("imagunity.dicom.routes[0].destination", () -> "box-b");
    }

    @Autowired
    private DicomProperties properties;

    @Autowired
    private DicomStoreScp scp;

    @Test
    void bindsConfiguration() {
        DicomInputConfig config = properties.toConfig();

        assertThat(config.port()).isEqualTo(PORT);
        assertThat(config.allowedCallingAets()).containsExactly("ORTHANC_A");
        assertThat(config.destinationFor("IU_TO_B")).contains("box-b");
        assertThat(config.studyIdleTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.minFreeSpace()).isEqualTo(10L * 1024 * 1024);
        assertThat(scp.studyIdleTimeout()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void opensDicomPortAtStartup() throws IOException {
        try (Socket socket = new Socket("127.0.0.1", PORT)) {
            assertThat(socket.isConnected()).isTrue();
        }
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
