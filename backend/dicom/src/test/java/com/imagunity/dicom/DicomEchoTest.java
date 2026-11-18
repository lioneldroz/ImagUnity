package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DicomEchoTest {

    @TempDir
    Path storage;

    @Test
    void answeringDeviceSucceedsAndRefusalsAreExplained() throws IOException {
        int port = freePort();
        DicomStoreScp scp = new DicomStoreScp(new DicomInputConfig("127.0.0.1", port, Set.of("BOX_NORD"),
                Map.of("PACS_NORD", "box-b"), storage, Duration.ofMillis(400), 0), s -> { });
        TestPorts.start(scp);
        try {
            assertThat(DicomEcho.echo("127.0.0.1", port, "PACS_NORD", "BOX_NORD", Duration.ofSeconds(5))).isEmpty();
            assertThat(DicomEcho.echo("127.0.0.1", port, "PACS_NORD", "INCONNU", Duration.ofSeconds(5)))
                    .hasValueSatisfying(r -> assertThat(r).contains("association refusée"));
        } finally {
            scp.stop();
        }
        assertThat(DicomEcho.echo("127.0.0.1", freePort(), "PACS_NORD", "BOX_NORD", Duration.ofSeconds(2)))
                .hasValueSatisfying(r -> assertThat(r).contains("connexion refusée"));
        assertThat(DicomEcho.echo("hote-inexistant.invalid", 104, "PACS_NORD", "BOX_NORD", Duration.ofSeconds(2)))
                .isPresent();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
