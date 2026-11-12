package com.imagunity.relay;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;

/** IU-MSG par HTTP réel : files, dépôt réservé aux box actives, relève par attente longue, acquittement. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class QueueApiTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        RelayFixture.start();
        registry.add("imagunity.relay.directory.url", RelayFixture::directoryUrl);
        registry.add("imagunity.relay.directory.organization-key", () -> RelayFixture.organizationKey.toString());
        registry.add("imagunity.relay.directory.organization-code",
                () -> RelayFixture.ORG_KEYS.publicKey().shortCode());
        registry.add("imagunity.relay.max-wait", () -> "2s");
        registry.add("imagunity.relay.default-ttl", () -> "1s");
        registry.add("imagunity.relay.storage-dir", () -> "./target/morceaux-" + UUID.randomUUID());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:files-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private Environment environment;
    @Autowired
    private LocalDirectory directory;
    @Autowired
    private QueueService queues;
    @Autowired
    private javax.sql.DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();
    private KeyPair queueKey;

    @BeforeEach
    void setUp() throws Exception {
        var jdbc = org.springframework.jdbc.core.simple.JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM queue_message").update();
        jdbc.sql("DELETE FROM queue").update();
        for (int i = 0; i < 100 && directory.entry(RelayFixture.ACTIVE).isEmpty(); i++) {
            Thread.sleep(50);
        }
        queueKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + environment.getProperty("local.server.port") + path;
    }

    private HttpResponse<byte[]> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest.Builder deposit(String path, UUID box, HybridKeyPair keys, byte[] body, String type) {
        SignedRequest signed = SignedRequest.of("POST", path, body, Instant.now());
        return HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", type)
                .header(SignedRequest.KEY_ID, box.toString())
                .header(SignedRequest.TIMESTAMP, Long.toString(signed.timestamp()))
                .header(SignedRequest.REQUEST_ID, signed.requestId())
                .header(SignedRequest.SIGNATURE, signed.signDeposit(keys))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
    }

    private HttpRequest.Builder capability(String method, String path, String id, PrivateKey key) {
        SignedRequest signed = SignedRequest.of(method, path, null, Instant.now());
        return HttpRequest.newBuilder(URI.create(url(path))).header(SignedRequest.KEY_ID, id)
                .header(SignedRequest.TIMESTAMP, Long.toString(signed.timestamp()))
                .header(SignedRequest.REQUEST_ID, signed.requestId())
                .header(SignedRequest.SIGNATURE, signed.signCapability(key))
                .method(method, HttpRequest.BodyPublishers.noBody());
    }

    private JsonNode createQueue() throws Exception {
        byte[] body = ("{\"suite\":\"IU1\",\"queueKey\":\"" + Codec.base64Url(queueKey.getPublic().getEncoded())
                + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpResponse<byte[]> created = send(deposit("/iu/v1/queues", RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                body, "application/json"));
        assertThat(created.statusCode()).as(new String(created.body(), StandardCharsets.UTF_8)).isEqualTo(201);
        return Codec.json().readTree(created.body());
    }

    private HttpResponse<byte[]> post(String sendId, String text) throws Exception {
        return send(deposit("/iu/v1/queues/" + sendId + "/messages", RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                text.getBytes(StandardCharsets.UTF_8), "application/octet-stream"));
    }

    @Test
    void messagesAreDepositedReceivedInOrderAndAcknowledged() throws Exception {
        JsonNode ids = createQueue();
        String sendId = ids.get("sendId").asString();
        String recvId = ids.get("recvId").asString();
        String messages = "/iu/v1/queues/" + recvId + "/messages";

        long start = System.nanoTime();
        HttpRequest.Builder empty = capability("GET", messages, recvId, queueKey.getPrivate());
        assertThat(send(empty.uri(URI.create(url(messages) + "?wait=60"))).statusCode()).isEqualTo(204);
        assertThat((System.nanoTime() - start) / 1_000_000).as("attente plafonnée à 2 s").isBetween(1800L, 6000L);

        assertThat(post(sendId, "enveloppe 1").statusCode()).isEqualTo(202);
        assertThat(post(sendId, "enveloppe 2").statusCode()).isEqualTo(202);
        HttpResponse<byte[]> received = send(capability("GET", messages, recvId, queueKey.getPrivate()));
        assertThat(received.statusCode()).isEqualTo(200);
        JsonNode list = Codec.json().readTree(received.body());
        assertThat(list).hasSize(2);
        assertThat(new String(Codec.fromBase64Url(list.get(0).get("content").asString(), "x"), StandardCharsets.UTF_8))
                .isEqualTo("enveloppe 1");
        String first = list.get(0).get("messageId").asString();

        assertThat(send(capability("DELETE", messages + "/" + first, recvId, queueKey.getPrivate())).statusCode())
                .isEqualTo(204);
        assertThat(send(capability("DELETE", messages + "/" + first, recvId, queueKey.getPrivate())).statusCode())
                .isEqualTo(404);
        assertThat(Codec.json().readTree(send(capability("GET", messages, recvId, queueKey.getPrivate())).body()))
                .hasSize(1);
        assertThat(send(capability("GET", messages, recvId,
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPrivate())).statusCode())
                .as("autre clé").isEqualTo(401);
        assertThat(send(capability("GET", "/iu/v1/queues/" + sendId + "/messages", sendId, queueKey.getPrivate()))
                .statusCode()).as("l'identifiant de dépôt ne permet pas de relever").isEqualTo(404);
    }

    private HttpResponse<byte[]> createWith(String sendId) throws Exception {
        byte[] body = ("{\"suite\":\"IU1\",\"queueKey\":\"" + Codec.base64Url(queueKey.getPublic().getEncoded())
                + "\",\"sendId\":\"" + sendId + "\"}").getBytes(StandardCharsets.UTF_8);
        return send(deposit("/iu/v1/queues", RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS, body, "application/json"));
    }

    @Test
    void aBoxCreatesTheQueueWhoseAddressItsDirectoryEntryPublishes() throws Exception {
        assertThat(RelayFixture.ACTIVE_SEND_ID).hasSize(43);
        assertThat(createWith("x".repeat(43)).statusCode()).as("pas l'adresse de son entrée").isEqualTo(409);
        assertThat(createWith("court").statusCode()).isEqualTo(400);
        HttpResponse<byte[]> created = createWith(RelayFixture.ACTIVE_SEND_ID);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode ids = Codec.json().readTree(created.body());
        assertThat(ids.get("sendId").asString()).isEqualTo(RelayFixture.ACTIVE_SEND_ID);
        assertThat(ids.get("recvId").asString()).isNotEqualTo(RelayFixture.ACTIVE_SEND_ID).hasSize(43);
        assertThat(createWith(RelayFixture.ACTIVE_SEND_ID).statusCode()).as("déjà créée").isEqualTo(409);
        assertThat(post(RelayFixture.ACTIVE_SEND_ID, "bonjour").statusCode()).isEqualTo(202);
    }

    @Test
    void depositIsReservedToActiveBoxesAndBounded() throws Exception {
        JsonNode ids = createQueue();
        String sendId = ids.get("sendId").asString();
        String path = "/iu/v1/queues/" + sendId + "/messages";
        byte[] text = "x".getBytes(StandardCharsets.UTF_8);

        assertThat(send(deposit(path, RelayFixture.REVOKED, RelayFixture.REVOKED_KEYS, text,
                "application/octet-stream")).statusCode()).isEqualTo(403);
        assertThat(send(deposit(path, RelayFixture.ACTIVE, HybridKeyPair.generate(), text,
                "application/octet-stream")).statusCode()).isEqualTo(401);
        assertThat(post("inconnue", "x").statusCode()).isEqualTo(404);
        assertThat(post(sendId, "").statusCode()).isEqualTo(413);
        assertThat(post(sendId, "x".repeat(QueueService.MAX_ENVELOPE + 1)).statusCode()).isEqualTo(413);

        for (int i = 1; i < 4; i++) {
            createQueue();
        }
        byte[] body = ("{\"suite\":\"IU1\",\"queueKey\":\"" + Codec.base64Url(queueKey.getPublic().getEncoded())
                + "\"}").getBytes(StandardCharsets.UTF_8);
        assertThat(send(deposit("/iu/v1/queues", RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS, body,
                "application/json")).statusCode()).as("nombre de files par box").isEqualTo(507);
        assertThat(send(deposit("/iu/v1/queues", RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                "{\"suite\":\"IU2\",\"queueKey\":\"x\"}".getBytes(StandardCharsets.UTF_8), "application/json"))
                .statusCode()).isEqualTo(400);
        assertThat(send(deposit("/iu/v1/queues", RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                "{\"inconnu\":1}".getBytes(StandardCharsets.UTF_8), "application/json")).statusCode()).isEqualTo(400);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queues.create(RelayFixture.ACTIVE,
                new QueueService.CreateRequest("IU1", "AAAA"))).hasMessageContaining("Clé publique");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> queues.create(RelayFixture.ACTIVE, null))
                .hasMessageContaining("Suite");
    }

    @Test
    void rotationKeepsPendingMessagesAndExpiredOnesArePurged() throws Exception {
        JsonNode ids = createQueue();
        String oldSend = ids.get("sendId").asString();
        String oldRecv = ids.get("recvId").asString();
        assertThat(post(oldSend, "avant renouvellement").statusCode()).isEqualTo(202);

        JsonNode rotated = Codec.json().readTree(send(capability("POST", "/iu/v1/queues/" + oldRecv + "/rotate",
                oldRecv, queueKey.getPrivate())).body());
        String newSend = rotated.get("sendId").asString();
        String newRecv = rotated.get("recvId").asString();

        assertThat(post(oldSend, "x").statusCode()).as("ancienne adresse").isEqualTo(404);
        String messages = "/iu/v1/queues/" + newRecv + "/messages";
        assertThat(Codec.json().readTree(send(capability("GET", messages, newRecv, queueKey.getPrivate())).body()))
                .hasSize(1);
        assertThat(post(newSend, "après").statusCode()).isEqualTo(202);

        Thread.sleep(1500); // durée de séjour d'essai : 1 s
        assertThat(send(capability("GET", messages, newRecv, queueKey.getPrivate())).statusCode())
                .as("messages expirés non rendus").isEqualTo(204);
        assertThat(queues.purge()).isEqualTo(2);
    }
}
