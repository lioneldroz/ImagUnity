package com.imagunity.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.protocol.ChunkLayout;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.SignedRequest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** IU-FILE par HTTP réel : dépôt réservé aux box actives, capacités, attente, reprise, acquittement, purge. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ChunkApiTest {

    private static final int SIZE = 256 * ChunkLayout.KIB;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        RelayFixture.start();
        registry.add("imagunity.relay.directory.url", RelayFixture::directoryUrl);
        registry.add("imagunity.relay.directory.organization-key", () -> RelayFixture.organizationKey.toString());
        registry.add("imagunity.relay.directory.organization-code",
                () -> RelayFixture.ORG_KEYS.publicKey().shortCode().toLowerCase().replace("-", " "));
        registry.add("imagunity.relay.quota-per-box", () -> "1MB");
        registry.add("imagunity.relay.max-wait", () -> "2s");
        registry.add("imagunity.relay.incomplete-ttl", () -> "1s");
        registry.add("imagunity.relay.storage-dir", () -> "./target/morceaux-" + UUID.randomUUID());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:relais-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private Environment environment;
    @Autowired
    private LocalDirectory directory;
    @Autowired
    private ChunkService chunks;
    @Autowired
    private javax.sql.DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private final byte[] content = new byte[SIZE];
    private KeyPair sender;
    private KeyPair recipient;

    @BeforeEach
    void setUp() throws Exception {
        var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM chunk_recipient").update();
        jdbc.sql("DELETE FROM chunk").update();
        for (int i = 0; i < 100 && directory.entry(RelayFixture.ACTIVE).isEmpty(); i++) {
            Thread.sleep(50);
        }
        new Random(5).nextBytes(content);
        sender = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        recipient = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + environment.getProperty("local.server.port") + path;
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private String createBody(int size, String digest, List<KeyPair> recipients, long ttl) {
        return "{\"suite\":\"IU1\",\"size\":" + size + ",\"digest\":\"" + digest + "\",\"senderKey\":\""
                + Codec.base64Url(sender.getPublic().getEncoded()) + "\",\"recipientKeys\":["
                + String.join(",", recipients.stream().map(k -> "\"" + Codec.base64Url(k.getPublic().getEncoded())
                        + "\"").toList()) + "],\"ttl\":" + ttl + "}";
    }

    private HttpRequest.Builder deposit(UUID box, HybridKeyPair keys, String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        SignedRequest signed = SignedRequest.of("POST", "/iu/v1/chunks", body, Instant.now());
        return HttpRequest.newBuilder(URI.create(url("/iu/v1/chunks"))).header("Content-Type", "application/json")
                .header(SignedRequest.KEY_ID, box.toString())
                .header(SignedRequest.TIMESTAMP, Long.toString(signed.timestamp()))
                .header(SignedRequest.REQUEST_ID, signed.requestId())
                .header(SignedRequest.SIGNATURE, signed.signDeposit(keys))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private HttpRequest.Builder capability(String method, String path, String id, PrivateKey key, byte[] body) {
        SignedRequest signed = SignedRequest.of(method, path, body, Instant.now());
        return HttpRequest.newBuilder(URI.create(url(path))).header(SignedRequest.KEY_ID, id)
                .header(SignedRequest.TIMESTAMP, Long.toString(signed.timestamp()))
                .header(SignedRequest.REQUEST_ID, signed.requestId())
                .header(SignedRequest.SIGNATURE, signed.signCapability(key))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private JsonNode create(long ttl) throws Exception {
        HttpResponse<byte[]> created = send(deposit(RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                createBody(SIZE, Codec.sha256Hex(content), List.of(recipient), ttl)));
        assertThat(created.statusCode()).as(new String(created.body(), StandardCharsets.UTF_8)).isEqualTo(201);
        return Codec.json().readTree(created.body());
    }

    @Test
    void chunkLifecycleFromCreationToAcknowledgement() throws Exception {
        JsonNode ids = create(3600);
        String senderId = ids.get("senderId").asString();
        String recipientId = ids.get("recipientIds").get(0).asString();
        assertThat(senderId).hasSize(43).isNotEqualTo(recipientId);
        String chunkPath = "/iu/v1/chunks/" + senderId;
        String recipientPath = "/iu/v1/chunks/" + recipientId;

        HttpResponse<byte[]> early = send(capability("GET", recipientPath, recipientId, recipient.getPrivate(), null));
        assertThat(early.statusCode()).isEqualTo(425);
        assertThat(early.headers().firstValue("Retry-After")).hasValue("2");
        long start = System.nanoTime();
        HttpRequest.Builder waiting = capability("GET", recipientPath, recipientId, recipient.getPrivate(), null);
        assertThat(send(waiting.uri(URI.create(url(recipientPath) + "?wait=60"))).statusCode()).isEqualTo(425);
        assertThat((System.nanoTime() - start) / 1_000_000).as("attente plafonnée à 2 s").isBetween(1800L, 6000L);

        byte[] other = content.clone();
        other[0] ^= 1;
        assertThat(send(capability("PUT", chunkPath, senderId, sender.getPrivate(), other)).statusCode())
                .as("contenu différent de l'annonce").isEqualTo(400);
        assertThat(send(capability("PUT", chunkPath, senderId, recipient.getPrivate(), content)).statusCode())
                .as("mauvaise clé").isEqualTo(401);
        assertThat(send(capability("PUT", chunkPath, senderId, sender.getPrivate(),
                Arrays.copyOf(content, SIZE + 10))).statusCode()).as("trop long").isEqualTo(413);
        assertThat(send(capability("PUT", chunkPath, senderId, sender.getPrivate(), content)).statusCode())
                .isEqualTo(204);
        assertThat(send(capability("PUT", chunkPath, senderId, sender.getPrivate(), content)).statusCode())
                .as("renvoi après une réponse perdue").isEqualTo(204);

        JsonNode state = Codec.json().readTree(send(capability("GET", chunkPath, senderId, sender.getPrivate(), null))
                .body());
        assertThat(state.get("uploaded").asBoolean()).isTrue();
        assertThat(state.get("recipientsAcked").asInt()).isZero();

        HttpResponse<byte[]> downloaded = send(capability("GET", recipientPath, recipientId, recipient.getPrivate(),
                null));
        assertThat(downloaded.statusCode()).isEqualTo(200);
        assertThat(downloaded.body()).isEqualTo(content);
        HttpResponse<byte[]> partial = send(capability("GET", recipientPath, recipientId, recipient.getPrivate(), null)
                .header("Range", "bytes=1000-"));
        assertThat(partial.statusCode()).as("reprise en cours de morceau").isEqualTo(206);
        assertThat(partial.body()).isEqualTo(Arrays.copyOfRange(content, 1000, SIZE));
        assertThat(send(capability("GET", recipientPath, recipientId, sender.getPrivate(), null)).statusCode())
                .isEqualTo(401);

        assertThat(send(capability("POST", recipientPath + "/ack", recipientId, recipient.getPrivate(), null))
                .statusCode()).isEqualTo(204);
        assertThat(send(capability("GET", recipientPath, recipientId, recipient.getPrivate(), null)).statusCode())
                .as("accès révoqué, morceau supprimé").isEqualTo(404);
        assertThat(send(capability("GET", chunkPath, senderId, sender.getPrivate(), null)).statusCode())
                .isEqualTo(404);
    }

    @Test
    void depositIsReservedToActiveEnrolledBoxes() throws Exception {
        String body = createBody(SIZE, Codec.sha256Hex(content), List.of(recipient), 3600);

        assertThat(send(deposit(RelayFixture.REVOKED, RelayFixture.REVOKED_KEYS, body)).body())
                .asString(StandardCharsets.UTF_8).contains("non active").contains("REVOKED");
        assertThat(send(deposit(UUID.randomUUID(), RelayFixture.ACTIVE_KEYS, body)).statusCode()).isEqualTo(403);
        assertThat(send(deposit(RelayFixture.ACTIVE, HybridKeyPair.generate(), body)).statusCode())
                .as("signée par une autre clé").isEqualTo(401);
        HttpRequest.Builder replayed = deposit(RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS, body);
        assertThat(send(replayed).statusCode()).isEqualTo(201);
        assertThat(send(replayed).statusCode()).as("rejouée").isEqualTo(401);
        assertThat(send(HttpRequest.newBuilder(URI.create(url("/iu/v1/chunks"))).header("Content-Type",
                "application/json").POST(HttpRequest.BodyPublishers.ofString(body))).statusCode()).isEqualTo(401);
        assertThat(send(deposit(RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS, body)
                .setHeader(SignedRequest.TIMESTAMP, "hier")).statusCode()).isEqualTo(401);
    }

    @Test
    void malformedCreationsAndQuotasAreRefused() throws Exception {
        String sha = Codec.sha256Hex(content);
        assertThat(send(deposit(RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                createBody(1000, sha, List.of(recipient), 0))).statusCode()).isEqualTo(400);
        assertThat(send(deposit(RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                createBody(SIZE, "x", List.of(recipient), 0))).statusCode()).isEqualTo(400);
        assertThat(send(deposit(RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                createBody(SIZE, sha, List.of(), 0))).statusCode()).isEqualTo(400);
        assertThat(send(deposit(RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                createBody(SIZE, sha, List.of(recipient), 0).replace("\"IU1\"", "\"IU2\""))).statusCode())
                .isEqualTo(400);
        assertThat(send(deposit(RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                createBody(SIZE, sha, List.of(recipient), 0).replaceFirst("\"senderKey\":\"[^\"]+\"",
                        "\"senderKey\":\"AAAA\""))).statusCode()).isEqualTo(400);
        assertThat(send(deposit(RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS, "{\"inconnu\":1}")).statusCode())
                .isEqualTo(400);
        // Quota d'essai : 1 Mo par box, soit 4 morceaux de 256 Kio au plus.
        int created = 0;
        int status = 201;
        while (status == 201 && created < 10) {
            status = send(deposit(RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                    createBody(SIZE, sha, List.of(recipient), 999_999_999))).statusCode();
            created++;
        }
        assertThat(status).as("volume maximal par box").isEqualTo(507);
    }

    @Test
    void expiredAndNeverUploadedChunksArePurged() throws Exception {
        JsonNode ids = create(1);
        String senderId = ids.get("senderId").asString();
        Thread.sleep(1500);

        new RelayConfiguration.Purge(chunks).run();
        assertThat(chunks.purge()).as("déjà purgé par la tâche planifiée").isZero();
        assertThat(send(capability("GET", "/iu/v1/chunks/" + senderId, senderId, sender.getPrivate(), null))
                .statusCode()).isEqualTo(404);
        assertThat(send(capability("DELETE", "/iu/v1/chunks/" + senderId, senderId, sender.getPrivate(), null))
                .statusCode()).isEqualTo(404);
    }

    @Test
    void senderCanDeleteItsChunk() throws Exception {
        JsonNode ids = create(3600);
        String senderId = ids.get("senderId").asString();
        String recipientId = ids.get("recipientIds").get(0).asString();

        assertThat(send(capability("DELETE", "/iu/v1/chunks/" + senderId, senderId, recipient.getPrivate(), null))
                .statusCode()).isEqualTo(401);
        assertThat(send(capability("DELETE", "/iu/v1/chunks/" + senderId, senderId, sender.getPrivate(), null))
                .statusCode()).isEqualTo(204);
        assertThat(send(capability("POST", "/iu/v1/chunks/" + recipientId + "/ack", recipientId,
                recipient.getPrivate(), null)).statusCode()).isEqualTo(404);
        assertThat(send(capability("GET", "/iu/v1/chunks/" + senderId, "autre-id", sender.getPrivate(), null))
                .statusCode()).isEqualTo(404);
    }
}
