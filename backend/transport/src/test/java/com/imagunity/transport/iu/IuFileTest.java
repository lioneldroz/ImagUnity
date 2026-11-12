package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SignedDocument;
import com.imagunity.protocol.ChunkLayout;
import com.imagunity.protocol.FileDescription;
import com.imagunity.relay.RelayApplication;
import com.imagunity.transport.TransportException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Client IU-FILE contre un vrai relais démarré en mémoire (H2), avec un annuaire simulé (données synthétiques). */
class IuFileTest {

    private static final HybridKeyPair ORG = HybridKeyPair.generate();
    private static final HybridKeyPair BOX_KEYS = HybridKeyPair.generate();
    private static final UUID BOX = UUID.randomUUID();
    private static HttpServer directory;
    private static ConfigurableApplicationContext relay;
    private static URI relayUrl;

    @TempDir
    Path dir;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Retry quick = new Retry(Duration.ofSeconds(3), Duration.ofSeconds(1), d -> { });

    @BeforeAll
    static void startRelay() throws Exception {
        Organization organization = new Organization("chu-exemple", ORG);
        Instant now = Instant.now();
        SignedDocument entry = organization.enroll(EnrollmentRequest.create(BOX_KEYS, BOX, "Site A", Map.of(), now),
                now.minusSeconds(60), Duration.ofDays(30), 1);
        directory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        directory.createContext("/directory/v1/entries", exchange -> {
            byte[] body = ("{\"organization\":\"chu-exemple\",\"entries\":[" + entry.toJson() + "]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        directory.start();
        Path key = Files.createTempFile("organisation", ".pub.json");
        KeyFiles.writeOrganizationPublic(key, "chu-exemple", ORG.publicKey());
        relay = new SpringApplicationBuilder(RelayApplication.class).run(
                "--server.port=0", "--spring.jmx.enabled=false",
                "--spring.datasource.url=jdbc:h2:mem:relais-client;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa",
                "--imagunity.relay.storage-dir=" + Files.createTempDirectory("relais"),
                "--imagunity.relay.min-free-space=1MB",
                "--imagunity.relay.directory.url=http://127.0.0.1:" + directory.getAddress().getPort(),
                "--imagunity.relay.directory.organization-key=" + key,
                "--imagunity.relay.directory.organization-code=" + ORG.publicKey().shortCode());
        relayUrl = URI.create("http://127.0.0.1:" + relay.getEnvironment().getProperty("local.server.port") + "/");
        com.imagunity.directory.LocalDirectory local = relay.getBean(com.imagunity.directory.LocalDirectory.class);
        for (int i = 0; i < 100 && local.entry(BOX).isEmpty(); i++) {
            Thread.sleep(50);
        }
    }

    @AfterAll
    static void stopRelay() {
        relay.close();
        directory.stop(0);
    }

    private IuFileClient client() {
        return new IuFileClient(relayUrl, http, Clock.systemUTC());
    }

    private IuFileUploader uploader() {
        return new IuFileUploader(client(), BOX, BOX_KEYS, Clock.systemUTC(), quick);
    }

    private Path packet(int size) throws IOException {
        byte[] content = new byte[size];
        new Random(size).nextBytes(content);
        return Files.write(dir.resolve("groupe-1-of-1.tar.zst.iu1"), content);
    }

    @Test
    void packetTravelsThroughTheRelayAndIsRemovedOnceAcknowledged() throws Exception {
        Path packet = packet(3 * ChunkLayout.MIB + 4321);
        IuFileUploader uploader = uploader();
        List<IuFileUploader.UploadState> checkpoints = new ArrayList<>();
        List<Long> sent = new ArrayList<>();

        IuFileUploader.UploadState prepared = uploader.prepare(packet, ChunkLayout.MIB, Duration.ofHours(1));
        assertThat(prepared.complete()).isFalse();
        IuFileUploader.UploadState done = uploader.upload(packet, prepared, checkpoints::add, sent::add);
        FileDescription description = IuFileUploader.description(done);

        assertThat(done.complete()).isTrue();
        assertThat(checkpoints).hasSize(4);
        assertThat(sent).last().isEqualTo(Files.size(packet));
        assertThat(FileDescription.fromJson(description.toJson())).isEqualTo(description);

        Path target = dir.resolve("recu.iu1");
        List<Long> received = new ArrayList<>();
        new IuFileDownloader(http, Clock.systemUTC(), quick).download(description, target, Set.of(), i -> { },
                received::add);
        assertThat(Files.readAllBytes(target)).isEqualTo(Files.readAllBytes(packet));
        assertThat(received).last().isEqualTo(Files.size(packet));

        IuFileUploader.ChunkState first = done.chunks().getFirst();
        assertThatThrownBy(() -> client().state(first.senderId(), IuFileUploader.privateKey(first.senderKey())))
                .isInstanceOfSatisfying(RelayRefusedException.class, e -> assertThat(e.status()).isEqualTo(404));
        uploader.delete(done); // déjà supprimé : sans effet
    }

    @Test
    void interruptedTransfersResumeFromTheirSavedState() throws Exception {
        Path packet = packet(2 * ChunkLayout.MIB + 10);
        IuFileUploader uploader = uploader();
        IuFileUploader.UploadState prepared = uploader.prepare(packet, ChunkLayout.MIB, Duration.ofHours(1));
        AtomicReference<String> saved = new AtomicReference<>();

        // Arrêt brutal après le premier morceau : l'état enregistré permet de reprendre.
        assertThatThrownBy(() -> uploader.upload(packet, prepared, state -> {
            saved.set(state.toJson());
            throw new IllegalStateException("arrêt de la box");
        }, n -> { })).hasMessageContaining("arrêt");
        IuFileUploader.UploadState resumed = IuFileUploader.UploadState.fromJson(saved.get());
        assertThat(resumed.chunks().stream().filter(IuFileUploader.ChunkState::uploaded)).hasSize(1);
        IuFileUploader.ChunkState first = resumed.chunks().getFirst();
        assertThat(client().state(first.senderId(), IuFileUploader.privateKey(first.senderKey())).uploaded()).isTrue();
        IuFileUploader.UploadState done = uploader.upload(packet, resumed, s -> { }, n -> { });
        assertThat(done.complete()).isTrue();

        // Réception interrompue après un morceau, puis reprise sans le retélécharger.
        FileDescription description = IuFileUploader.description(done);
        Path target = dir.resolve("recu.iu1");
        Set<Integer> written = new HashSet<>();
        IuFileDownloader downloader = new IuFileDownloader(http, Clock.systemUTC(), quick);
        assertThatThrownBy(() -> downloader.download(description, target, Set.of(), index -> {
            written.add(index);
            throw new IllegalStateException("arrêt de la box");
        }, n -> { })).hasMessageContaining("arrêt");
        downloader.download(description, target, written, written::add, n -> { });
        assertThat(Files.readAllBytes(target)).isEqualTo(Files.readAllBytes(packet));
    }

    @Test
    void unenrolledBoxesAndAlteredPacketsAreRefused() throws Exception {
        Path packet = packet(ChunkLayout.MIB);
        IuFileUploader stranger = new IuFileUploader(client(), UUID.randomUUID(), HybridKeyPair.generate(),
                Clock.systemUTC(), quick);
        assertThatThrownBy(() -> stranger.prepare(packet, ChunkLayout.MIB, Duration.ofHours(1)))
                .isInstanceOfSatisfying(RelayRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(403);
                    assertThat(e.retryable()).isFalse();
                    assertThat(e).hasMessageContaining("absente de l'annuaire");
                });

        IuFileUploader uploader = uploader();
        IuFileUploader.UploadState prepared = uploader.prepare(packet, ChunkLayout.MIB, Duration.ofHours(1));
        byte[] modified = Files.readAllBytes(packet);
        modified[0] ^= 1;
        Files.write(packet, modified);
        assertThatThrownBy(() -> uploader.upload(packet, prepared, s -> { }, n -> { }))
                .hasMessageContaining("Paquet modifié");
        Files.write(packet, new byte[10]);
        assertThatThrownBy(() -> uploader.upload(packet, prepared, s -> { }, n -> { }))
                .hasMessageContaining("Paquet modifié");
        uploader.delete(prepared);
    }

    @Test
    void downloadedChunksAreVerified() throws Exception {
        Path packet = packet(ChunkLayout.MIB + 7);
        IuFileUploader uploader = uploader();
        IuFileUploader.UploadState done = uploader.upload(packet,
                uploader.prepare(packet, ChunkLayout.MIB, Duration.ofHours(1)), s -> { }, n -> { });
        FileDescription genuine = IuFileUploader.description(done);
        List<FileDescription.Chunk> chunks = new ArrayList<>(genuine.chunks());
        chunks.set(0, new FileDescription.Chunk(0, "0".repeat(64), chunks.get(0).replicas()));
        FileDescription altered = new FileDescription("IU1", genuine.transferId(), genuine.mode(), genuine.fileName(),
                genuine.chunkSize(), genuine.chunkCount(), genuine.size(), genuine.digest(), chunks,
                genuine.expiresAt());
        IuFileDownloader downloader = new IuFileDownloader(http, Clock.systemUTC(), quick);

        assertThatThrownBy(() -> downloader.download(altered, dir.resolve("a.iu1"), Set.of(), i -> { }, n -> { }))
                .isInstanceOf(TransportException.class).hasMessageContaining("altéré");
        FileDescription wrongDigest = new FileDescription("IU1", genuine.transferId(), genuine.mode(),
                genuine.fileName(), genuine.chunkSize(), genuine.chunkCount(), genuine.size(), "0".repeat(64),
                genuine.chunks(), genuine.expiresAt());
        assertThatThrownBy(() -> downloader.download(wrongDigest, dir.resolve("b.iu1"), Set.of(), i -> { }, n -> { }))
                .hasMessageContaining("réassemblé différent");
    }

    @Test
    void notYetUploadedChunksAreAwaitedThenReportedAsTooEarly() throws Exception {
        Path packet = packet(ChunkLayout.MIB);
        IuFileUploader.UploadState prepared = uploader().prepare(packet, ChunkLayout.MIB, Duration.ofHours(1));
        IuFileUploader.ChunkState chunk = prepared.chunks().getFirst();

        assertThatThrownBy(() -> client().download(chunk.recipientId(),
                IuFileUploader.privateKey(chunk.retrievalKey()), ChunkLayout.MIB, Duration.ZERO))
                .isInstanceOfSatisfying(RelayRefusedException.class, e -> {
                    assertThat(e.status()).isEqualTo(425);
                    assertThat(e.retryable()).isTrue();
                });
        assertThat(IuFileClient.refused(502, "pas du json".getBytes(StandardCharsets.UTF_8)))
                .hasMessageContaining("pas du json");
    }

    @Test
    void commandLineToolSendsReceivesAndResumes() throws Exception {
        Path keys = dir.resolve("box.json");
        KeyFiles.writePrivate(keys, BOX_KEYS, "phrase de la box d'essai".toCharArray());
        Files.writeString(Path.of(keys + ".id"), BOX.toString());
        Path packet = packet(ChunkLayout.MIB + 99);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
        IuFileTool tool = new IuFileTool(new java.io.PrintStream(out, true, StandardCharsets.UTF_8),
                new java.io.PrintStream(err, true, StandardCharsets.UTF_8),
                Map.of("IMAGUNITY_PHRASE_BOX", "phrase de la box d'essai"), http, quick);
        Path state = dir.resolve("etat.json");
        Path description = dir.resolve("description.json");
        String[] send = {"envoyer", "--relais", relayUrl.toString(), "--cles", keys.toString(), "--fichier",
            packet.toString(), "--etat", state.toString(), "--description", description.toString(), "--morceau-mio",
            "1"};

        assertThat(tool.run(send)).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Morceaux créés sur le relais : 2")
                .contains("Fichier téléversé");
        assertThat(tool.run(send)).as("reprise d'un envoi terminé").isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Reprise : 2 morceau(x) déjà téléversé(s)");

        Path received = dir.resolve("recu.iu1");
        Files.writeString(Path.of(received + ".fait"), "\n");
        assertThat(tool.run(new String[] {"recevoir", "--description", description.toString(), "--sortie",
            received.toString()})).as(err.toString(StandardCharsets.UTF_8)).isZero();
        assertThat(Files.readAllBytes(received)).isEqualTo(Files.readAllBytes(packet));
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Fichier reçu et vérifié").contains("Reprise : 0");

        assertThat(tool.run(new String[0])).isEqualTo(2);
        assertThat(tool.run(new String[] {"inconnue", "--a", "b"})).isEqualTo(2);
        assertThat(tool.run(new String[] {"envoyer", "--relais"})).isEqualTo(2);
        assertThat(tool.run(new String[] {"envoyer", "relais", "x"})).isEqualTo(2);
        assertThat(tool.run(new String[] {"recevoir", "--sortie", "x"})).isEqualTo(2);
        assertThat(tool.run(new String[] {"recevoir", "--description", dir.resolve("absente").toString(),
            "--sortie", "x"})).isEqualTo(1);
    }
}
