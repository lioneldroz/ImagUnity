package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.protocol.ChunkLayout;
import com.imagunity.protocol.FileDescription;
import com.imagunity.transport.TransportException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Copies d'un envoi et bascule entre répliques au téléchargement (spécification § 6.7), données synthétiques. */
class IuFileReplicasTest {

    private static final URI DEAD = URI.create("http://127.0.0.1:1");

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Retry quick = new Retry(Duration.ofSeconds(3), Duration.ofSeconds(1), d -> { });

    @BeforeAll
    static void startRelay() throws Exception {
        RelayHarness.start();
    }

    private IuFileUploader uploader() {
        return new IuFileUploader(new IuFileClient(RelayHarness.relayUrl, http, Clock.systemUTC()), RelayHarness.A,
                RelayHarness.A_KEYS, Clock.systemUTC(), quick);
    }

    private Path packet(String name, int seed) throws IOException {
        byte[] content = new byte[300 * ChunkLayout.KIB];
        new Random(seed).nextBytes(content);
        return Files.write(dir.resolve(name), content);
    }

    private IuFileDownloader downloader() {
        return new IuFileDownloader(http, Clock.systemUTC(), quick);
    }

    /** Même copie, annoncée sur un autre relais (injoignable). */
    private static IuFileUploader.UploadState elsewhere(IuFileUploader.UploadState state, URI relay) {
        return new IuFileUploader.UploadState(state.transferId(), state.fileName(), relay.toString(),
                state.chunkSize(), state.size(), state.digest(), state.expiresAt(), state.chunks(),
                state.paddingKey());
    }

    private Path download(List<IuFileUploader.UploadState> copies) throws IOException {
        Path target = dir.resolve("recu-" + System.nanoTime() + ".iu1");
        List<Integer> written = new ArrayList<>();
        downloader().download(IuFileUploader.description(copies, false), target, Set.of(), written::add, n -> { });
        assertThat(written).hasSize(2);
        return target;
    }

    @Test
    void copiesShareChunksButNotIdentifiers() throws Exception {
        Path packet = packet("paquet.iu1", 1);
        IuFileUploader uploader = uploader();
        IuFileUploader.UploadState first = uploader.prepare(packet, 256 * ChunkLayout.KIB, Duration.ofHours(1));
        IuFileUploader.UploadState copy = uploader.prepareCopy(first);
        assertThat(copy.transferId()).isEqualTo(first.transferId());
        assertThat(copy.paddingKey()).isEqualTo(first.paddingKey());
        assertThat(copy.chunks()).extracting(IuFileUploader.ChunkState::digest)
                .isEqualTo(first.chunks().stream().map(IuFileUploader.ChunkState::digest).toList());
        assertThat(copy.chunks()).extracting(IuFileUploader.ChunkState::recipientId)
                .doesNotContainAnyElementsOf(first.chunks().stream().map(IuFileUploader.ChunkState::recipientId)
                        .toList());
        FileDescription description = IuFileUploader.description(List.of(first, copy), true);
        assertThat(description.mode()).isEqualTo(FileDescription.EARLY);
        assertThat(description.chunks()).allSatisfy(c -> assertThat(c.replicas()).hasSize(2));

        IuFileUploader.UploadState expired = new IuFileUploader.UploadState(first.transferId(), first.fileName(),
                first.relay(), first.chunkSize(), first.size(), first.digest(), Instant.now().minusSeconds(1),
                first.chunks(), first.paddingKey());
        assertThatThrownBy(() -> uploader.prepareCopy(expired)).isInstanceOf(TransportException.class)
                .hasMessageContaining("expiré");
        uploader.delete(first);
        uploader.delete(copy);
    }

    @Test
    void theRecipientSwitchesToTheNextReplicaOnAnUnreachableRefusedOrAlteredOne() throws Exception {
        Path packet = packet("paquet.iu1", 2);
        IuFileUploader uploader = uploader();
        IuFileUploader.UploadState first = uploader.upload(packet,
                uploader.prepare(packet, 256 * ChunkLayout.KIB, Duration.ofHours(1)), s -> { }, n -> { });

        // Relais injoignable, puis la réplique suivante.
        IuFileUploader.UploadState copy = uploader.upload(packet, uploader.prepareCopy(first), s -> { }, n -> { });
        assertThat(Files.mismatch(packet, download(List.of(elsewhere(copy, DEAD), copy)))).isEqualTo(-1);

        // Réplique retirée (404) : écartée.
        IuFileUploader.UploadState removed = uploader.prepareCopy(first);
        uploader.delete(removed);
        IuFileUploader.UploadState other = uploader.upload(packet, uploader.prepareCopy(first), s -> { }, n -> { });
        assertThat(Files.mismatch(packet, download(List.of(removed, other)))).isEqualTo(-1);

        // Réplique d'un autre contenu, sous les empreintes de ce paquet : altérée, écartée.
        Path stranger = packet("autre.iu1", 3);
        IuFileUploader.UploadState strangerState = uploader.upload(stranger,
                uploader.prepare(stranger, 256 * ChunkLayout.KIB, Duration.ofHours(1)), s -> { }, n -> { });
        List<IuFileUploader.ChunkState> forged = new ArrayList<>();
        for (int i = 0; i < strangerState.chunks().size(); i++) {
            IuFileUploader.ChunkState c = strangerState.chunks().get(i);
            forged.add(new IuFileUploader.ChunkState(c.index(), first.chunks().get(i).digest(), c.senderId(),
                    c.senderKey(), c.recipientId(), c.retrievalKey(), true));
        }
        IuFileUploader.UploadState altered = new IuFileUploader.UploadState(first.transferId(), first.fileName(),
                first.relay(), first.chunkSize(), first.size(), first.digest(), first.expiresAt(), forged,
                first.paddingKey());
        IuFileUploader.UploadState last = uploader.upload(packet, uploader.prepareCopy(first), s -> { }, n -> { });
        assertThat(Files.mismatch(packet, download(List.of(altered, last)))).isEqualTo(-1);
        uploader.delete(first);
        uploader.delete(strangerState);
    }

    @Test
    void aRelayThatFallsSilentMidChunkIsLeftForTheNextReplica() throws Exception {
        com.sun.net.httpserver.HttpServer silent = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        silent.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        silent.createContext("/iu/v1/chunks", exchange -> {
            exchange.sendResponseHeaders(200, 256 * ChunkLayout.KIB);
            exchange.getResponseBody().write(new byte[1000]);
            exchange.getResponseBody().flush();
            try {
                Thread.sleep(10_000); // relais disparu sans fermer la connexion
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        silent.start();
        Duration before = IuFileClient.idleTimeout;
        IuFileClient.idleTimeout = Duration.ofMillis(400);
        try {
            Path packet = packet("paquet.iu1", 5);
            IuFileUploader uploader = uploader();
            IuFileUploader.UploadState first = uploader.upload(packet,
                    uploader.prepare(packet, 256 * ChunkLayout.KIB, Duration.ofHours(1)), s -> { }, n -> { });
            URI silentUrl = URI.create("http://127.0.0.1:" + silent.getAddress().getPort());
            long start = System.nanoTime();
            assertThat(Files.mismatch(packet, download(List.of(elsewhere(first, silentUrl), first)))).isEqualTo(-1);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(8));
        } finally {
            IuFileClient.idleTimeout = before;
            silent.stop(0);
        }
    }

    @Test
    void theDownloadFailsOnceNoReplicaIsLeftOrPatienceRunsOut() throws Exception {
        Path packet = packet("paquet.iu1", 4);
        IuFileUploader uploader = uploader();
        IuFileUploader.UploadState first = uploader.prepare(packet, 256 * ChunkLayout.KIB, Duration.ofHours(1));
        IuFileUploader.UploadState copy = uploader.prepareCopy(first);
        uploader.delete(first);
        uploader.delete(copy);
        assertThatThrownBy(() -> download(List.of(first, copy)))
                .isInstanceOfSatisfying(RelayRefusedException.class, e -> assertThat(e.status()).isEqualTo(404));
        assertThatThrownBy(() -> download(List.of(elsewhere(first, DEAD))))
                .isInstanceOf(TransportException.class).hasMessageContaining("impossible");
    }

    @Test
    void settingsCheckTheRelaysAndCountTheCopies() {
        Path state = dir.resolve("etat");
        Duration hour = Duration.ofHours(1);
        URI one = URI.create("http://relais-1:8443/");
        URI two = URI.create("http://relais-2:8443");
        assertThatThrownBy(() -> new IuTransport.Settings(List.of(), state, hour, 1, hour, true, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IuTransport.Settings(List.of(one), state, hour, 1, hour, true, 0, -1))
                .isInstanceOf(IllegalArgumentException.class);
        IuTransport.Settings all = new IuTransport.Settings(List.of(one, two, URI.create("http://relais-1:8443")),
                state, hour, 1, hour, true, 0, 0);
        assertThat(all.relays()).containsExactly(URI.create("http://relais-1:8443"), two);
        assertThat(all.relay()).isEqualTo(URI.create("http://relais-1:8443"));
        assertThat(all.copies()).isEqualTo(2);
        assertThat(new IuTransport.Settings(List.of(one, two), state, hour, 1, hour, true, 0, 1).copies())
                .isEqualTo(1);
        assertThat(new IuTransport.Settings(List.of(one, two), state, hour, 1, hour, true, 0, 5).copies())
                .isEqualTo(2);
    }

    @Test
    void anOutboxCopyForARemovedQueueIsDropped() {
        IuStore store = new IuStore(dir.resolve("boite"));
        QueueAddress first = QueueAddress.parse(DEAD + "/iu/v1/queues/" + "a".repeat(43));
        QueueAddress second = QueueAddress.parse(DEAD + "/iu/v1/queues/" + "b".repeat(43));
        List<QueueAddress> addresses = new ArrayList<>(List.of(first, second));
        IuOutbox outbox = new IuOutbox(store, new IuMsgClient(http, Clock.systemUTC()), RelayHarness.A,
                RelayHarness.A_KEYS, id -> List.copyOf(addresses), Clock.systemUTC(), Duration.ofDays(1));
        outbox.enqueue(RelayHarness.B, new byte[] {1});
        assertThat(store.pending()).extracting(IuStore.Pending::slot).containsExactly(0, 1);
        addresses.remove(second);
        assertThat(outbox.flush()).as("première file injoignable : gardé").isFalse();
        assertThat(store.pending()).extracting(IuStore.Pending::slot).containsExactly(0);
    }
}
