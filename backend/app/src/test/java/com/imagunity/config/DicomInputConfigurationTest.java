package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.dicom.DicomStoreScp;
import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.SmartLifecycle;
import org.springframework.util.unit.DataSize;

/** Câblage de l'entrée DICOM, sans contexte Spring. */
class DicomInputConfigurationTest {

    @TempDir
    Path storage;

    private final DicomInputConfiguration configuration = new DicomInputConfiguration();

    private DicomProperties properties(int port, List<DicomProperties.Route> routes) {
        return new DicomProperties(true, "127.0.0.1", port, Set.of("ORTHANC_A"), routes, storage,
                Duration.ofSeconds(1), DataSize.ofMegabytes(1));
    }

    @Test
    void routingRulesAreBuiltFromTheConfiguration() {
        DicomProperties properties = new DicomProperties(true, "127.0.0.1", 11112, Set.of("ORTHANC_A"), List.of(),
                storage, Duration.ofSeconds(1), DataSize.ofMegabytes(1), "IU_AUTO", List.of(
                        new DicomProperties.Rule("irm", java.util.Map.of("modality", "MR"), List.of("box-b", "box-c"))));

        var config = properties.toConfig();

        assertThat(config.routingAet()).isEqualTo("IU_AUTO");
        assertThat(config.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.describe()).isEqualTo("irm (modality=MR) → box-b, box-c");
        });
        assertThat(new DicomProperties(true, null, 11112, Set.of(), List.of(), storage, Duration.ofSeconds(1),
                DataSize.ofMegabytes(1), null, null).toConfig().rules()).isEmpty();
    }

    @Test
    void rejectsDuplicateDestination() {
        var routes = List.of(new DicomProperties.Route("IU_TO_B", "box-b"), new DicomProperties.Route("IU_TO_B", "box-c"));

        assertThatThrownBy(() -> properties(11112, routes).toConfig())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IU_TO_B");
    }

    @Test
    void lifecycleStartsAndStopsTheScp() throws IOException {
        DicomStoreScp scp = configuration.dicomStoreScp(
                properties(freePort(), List.of(new DicomProperties.Route("IU_TO_B", "box-b"))),
                study -> { });
        SmartLifecycle lifecycle = configuration.dicomInputLifecycle(scp);

        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();

        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void lifecycleFailsWhenPortIsTaken() throws IOException {
        try (ServerSocket taken = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))) {
            DicomStoreScp scp = configuration.dicomStoreScp(
                    properties(taken.getLocalPort(), List.of(new DicomProperties.Route("IU_TO_B", "box-b"))),
                    study -> { });
            SmartLifecycle lifecycle = configuration.dicomInputLifecycle(scp);

            assertThatThrownBy(lifecycle::start)
                    .isInstanceOf(UncheckedIOException.class)
                    .hasMessageContaining("entrée DICOM");
            assertThat(lifecycle.isRunning()).isFalse();
            scp.stop();
        }
    }

    @Test
    void receivedStudiesGoToTheOrchestrator() {
        Instant now = Instant.now();
        ReceivedStudy study = new ReceivedStudy("2.25.1", "IU_TO_B", "box-b", Set.of("ORTHANC_A"),
                List.of(new ReceivedInstance("1.2", "2.25.1.1.1", "2.25.1.1", "1.2", Path.of("x.dcm"), 10)), now, now);
        List<Runnable> scheduled = new java.util.ArrayList<>();
        var orchestrator = new com.imagunity.orchestration.SendOrchestrator(null, null, null, null, storage,
                scheduled::add, java.time.Clock.systemUTC());

        configuration.studyListener(orchestrator).studyReceived(study);

        assertThat(scheduled).as("étude confiée au fil de l'orchestrateur").hasSize(1);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
