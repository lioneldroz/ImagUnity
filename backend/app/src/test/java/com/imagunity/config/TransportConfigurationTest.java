package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.transport.DisabledTransport;
import com.imagunity.transport.TransportException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TransportConfigurationTest {

    private final TransportConfiguration configuration = new TransportConfiguration();
    private final List<Duration> pauses = new ArrayList<>();

    @Test
    void disabledTransportWhenSwitchedOff() {
        var properties = new TransportProperties(false, URI.create("ws://localhost:1"), Duration.ofSeconds(1),
                Duration.ofSeconds(1));

        assertThat(configuration.transport(properties)).isInstanceOf(DisabledTransport.class);
    }

    @Test
    void retriesUntilTheClientAnswers() {
        AtomicInteger attempts = new AtomicInteger();

        String result = TransportConfiguration.connectWithRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new TransportException("pas encore prêt");
            }
            return "connecté";
        }, Duration.ofMinutes(1), Duration.ofMillis(1), pauses::add);

        assertThat(result).isEqualTo("connecté");
        assertThat(pauses).hasSize(2);
    }

    @Test
    void givesUpAfterTheTimeout() {
        assertThatThrownBy(() -> TransportConfiguration.connectWithRetry(() -> {
            throw new TransportException("injoignable");
        }, Duration.ZERO, Duration.ofMillis(1), pauses::add))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("injoignable après")
                .hasCauseInstanceOf(TransportException.class);
        assertThat(pauses).isEmpty();
    }

    @Test
    void interruptionStopsWaiting() {
        try {
            assertThatThrownBy(() -> TransportConfiguration.connectWithRetry(() -> {
                throw new TransportException("injoignable");
            }, Duration.ofMinutes(1), Duration.ofMillis(1), d -> {
                throw new InterruptedException();
            }))
                    .isInstanceOf(TransportException.class)
                    .hasMessageContaining("interrompue");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void enabledTransportFailsWhenNoClientListens() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var properties = new TransportProperties(true, URI.create("ws://127.0.0.1:" + port), Duration.ZERO,
                Duration.ofSeconds(1));

        assertThatThrownBy(() -> configuration.transport(properties))
                .isInstanceOf(TransportException.class)
                .hasMessageContaining("injoignable");
    }
}
