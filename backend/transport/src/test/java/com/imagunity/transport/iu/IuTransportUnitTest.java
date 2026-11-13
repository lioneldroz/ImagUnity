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
    void aDiscardRequestedWhileTheFileIsBusyIsDoneAfterwards() throws Exception {
        // Relais simulé dont la suppression d'un morceau reste bloquée jusqu'au feu vert du test.
        java.util.concurrent.CountDownLatch deleting = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/iu/v1/chunks", exchange -> {
            deleting.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        URI relay = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try (IuTransport transport = new IuTransport(new IuTransport.Settings(relay, dir.resolve("retrait"),
                Duration.ofHours(1), 1, Duration.ofDays(8)), UUID.randomUUID(), HybridKeyPair.generate(),
                HybridKemKeyPair.generate(), none, HttpClient.newHttpClient(), Clock.systemUTC(),
                new Retry(Duration.ZERO, Duration.ofMillis(1), d -> { }))) {
            String key = com.imagunity.protocol.Codec.base64Url(java.security.KeyPairGenerator.getInstance("Ed25519")
                    .generateKeyPair().getPrivate().getEncoded());
            IuFileUploader.UploadState state = new IuFileUploader.UploadState("t", "p.tar.zst", relay.toString(),
                    256 * 1024, 10, "00", Instant.now().plusSeconds(3600), java.util.List.of(new IuFileUploader.ChunkState(0,
                    "00", "morceau", key, "r", key, true)), "AA");
            transport.store().save(new IuStore.Outgoing(7, 1, UUID.randomUUID(), "x", state, true, Instant.now()));

            transport.discardSentFile(7); // retrait en cours, bloqué sur le relais
            assertThat(deleting.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            transport.store().save(new IuStore.Outgoing(7, 1, UUID.randomUUID(), "x", state, true, Instant.now()));
            transport.discardSentFile(7); // demandé pendant le travail en cours sur ce fichier : retenu
            release.countDown();

            for (int i = 0; i < 250 && (transport.busy() || transport.store().outgoing(7).isPresent()); i++) {
                Thread.sleep(20);
            }
            // Repris à la fin du premier, puis terminé : rien ne reste, et aucune relance sans fin.
            assertThat(transport.store().outgoing(7)).isEmpty();
            Thread.sleep(100);
            assertThat(transport.busy()).isFalse();
        } finally {
            server.stop(0);
        }
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
