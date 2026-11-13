package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.transport.TransportEvent;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Parties de l'adaptateur IU-T qui ne demandent pas de relais : reprise des partenaires, réglages, arrêt. */
class IuTransportUnitTest {

    @TempDir
    Path dir;

    private final IuDirectory none = new IuDirectory() {
        @Override
        public Optional<Partner> partner(UUID boxId) {
            return Optional.empty();
        }

        @Override
        public Optional<Partner> byAddress(String address) {
            return Optional.empty();
        }
    };

    private IuTransport transport(Path state) {
        return new IuTransport(new IuTransport.Settings(URI.create("http://127.0.0.1:1/"), state, Duration.ofHours(1),
                1, Duration.ofDays(8)), UUID.randomUUID(), HybridKeyPair.generate(), HybridKemKeyPair.generate(), none,
                HttpClient.newHttpClient(), Clock.systemUTC(), new Retry(Duration.ZERO, Duration.ofMillis(1), d -> { }));
    }

    @Test
    void aClosedTransportStartsNoTransfer() {
        IuTransport transport = transport(dir.resolve("ferme"));
        transport.store().save(new IuStore.Outgoing(7, 1, UUID.randomUUID(), "x", null, true, Instant.now()));
        transport.close();
        transport.discardSentFile(7);
        assertThat(transport.busy()).isFalse();
        assertThat(transport.store().outgoing(7)).as("rien n'a été lancé").isPresent();
    }

    @Test
    void defaultsAndEdgeCases() throws Exception {
        assertThat(new TransportEvent.FileProgress(1, TransportEvent.Direction.SENDING, 5, 0).ratio()).isZero();
        assertThat(new TransportEvent.FileProgress(1, TransportEvent.Direction.SENDING, 5, 10).ratio()).isEqualTo(0.5);
        Retry standard = Retry.standard();
        assertThat(standard.patience()).isEqualTo(Duration.ofMinutes(15));
        assertThat(standard.maxDelay()).isEqualTo(Duration.ofSeconds(30));
        IuMsgClient client = new IuMsgClient(HttpClient.newHttpClient(), Clock.systemUTC());
        assertThatThrownBy(() -> client.createQueue(URI.create("http://127.0.0.1:1"), UUID.randomUUID(),
                HybridKeyPair.generate(), KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic()))
                .isInstanceOf(IOException.class);
    }
}
