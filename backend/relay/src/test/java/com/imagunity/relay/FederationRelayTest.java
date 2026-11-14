package com.imagunity.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.Organization;
import com.imagunity.directory.Recognition;
import com.imagunity.directory.SignedDocument;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.SignedRequest;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Fédération côté relais : une box d'une organisation partenaire reconnue peut déposer des messages dans la file d'une
 * box locale couverte par l'accord, et rien d'autre (données synthétiques).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FederationRelayTest {

    private static final Organization PARTNER = new Organization("bourgogne-fc", HybridKeyPair.generate());
    private static final String PARTNER_RELAY = "https://relais.bourgogne-fc.example";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        RelayFixture.start();
        registry.add("imagunity.relay.directory.url", RelayFixture::directoryUrl);
        registry.add("imagunity.relay.directory.organization-key", () -> RelayFixture.organizationKey.toString());
        registry.add("imagunity.relay.directory.organization-code",
                () -> RelayFixture.ORG_KEYS.publicKey().shortCode());
        registry.add("imagunity.relay.storage-dir", () -> "./target/morceaux-" + UUID.randomUUID());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:federation-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private Environment environment;
    @Autowired
    private LocalDirectory directory;

    private final HttpClient http = HttpClient.newHttpClient();
    private final HybridKeyPair foreignKeys = HybridKeyPair.generate();
    private final UUID foreign = UUID.randomUUID();
    private Recognition.Partner partner;

    @BeforeEach
    void setUp() throws Exception {
        for (int i = 0; i < 100 && directory.entry(RelayFixture.ACTIVE).isEmpty(); i++) {
            Thread.sleep(50);
        }
        partner = new Recognition.Partner(PARTNER.id(), PARTNER.publicKey().encoded(),
                "https://annuaire.bourgogne-fc.example", List.of(PARTNER_RELAY));
    }

    private void agreement(List<String> localGroups, List<String> partnerGroups, long serial) {
        Instant now = Instant.now();
        SignedDocument first = RelayFixture.ORGANIZATION.recognize(partner, new Recognition.Scope(localGroups,
                partnerGroups), now, Duration.ofDays(90));
        SignedDocument document = first;
        for (long s = 2; s <= serial; s++) {
            document = RelayFixture.ORGANIZATION.renewRecognition(document, partner, new Recognition.Scope(localGroups,
                    partnerGroups), now, Duration.ofDays(90));
        }
        SignedDocument recognition = document;
        directory.synchronizeRecognitions(() -> List.of(recognition));
        SignedDocument entry = PARTNER.enroll(EnrollmentRequest.create(foreignKeys, foreign, "Box de Bourgogne",
                Map.of("iu", PARTNER_RELAY + "/iu/v1/queues/q"), now), now.minusSeconds(60), Duration.ofDays(30), 1);
        directory.synchronizePartner(partner.fingerprint(), () -> List.of(entry));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + environment.getProperty("local.server.port") + path;
    }

    private HttpResponse<String> deposit(String path, UUID box, HybridKeyPair keys, byte[] body, String type)
            throws Exception {
        SignedRequest signed = SignedRequest.of("POST", path, body, Instant.now());
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).header("Content-Type", type)
                .header(SignedRequest.KEY_ID, box.toString())
                .header(SignedRequest.TIMESTAMP, Long.toString(signed.timestamp()))
                .header(SignedRequest.REQUEST_ID, signed.requestId())
                .header(SignedRequest.SIGNATURE, signed.signDeposit(keys))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] queueRequest() throws Exception {
        return ("{\"suite\":\"IU1\",\"queueKey\":\"" + Codec.base64Url(KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair().getPublic().getEncoded()) + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void partnerBoxesMayOnlyDepositMessagesWithinTheAgreement() throws Exception {
        HttpResponse<String> created = deposit("/iu/v1/queues", RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS,
                queueRequest(), "application/json");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String sendId = Codec.json().readTree(created.body()).get("sendId").asString();
        String messages = "/iu/v1/queues/" + sendId + "/messages";
        byte[] envelope = "enveloppe chiffrée".getBytes(StandardCharsets.UTF_8);

        // Sans accord : box inconnue du relais.
        assertThat(deposit(messages, foreign, foreignKeys, envelope, "application/octet-stream").statusCode())
                .isEqualTo(401);

        // Accord couvrant la box locale : dépôt de messages accepté, rien d'autre.
        agreement(List.of("*"), List.of("*"), 1);
        assertThat(deposit(messages, foreign, foreignKeys, envelope, "application/octet-stream").statusCode())
                .isEqualTo(202);
        HttpResponse<String> queue = deposit("/iu/v1/queues", foreign, foreignKeys, queueRequest(),
                "application/json");
        assertThat(queue.statusCode()).isEqualTo(403);
        assertThat(queue.body()).contains("seul le dépôt de messages");
        assertThat(deposit("/iu/v1/chunks", foreign, foreignKeys, "{}".getBytes(StandardCharsets.UTF_8),
                "application/json").statusCode()).isEqualTo(403);

        // Accord qui ne couvre pas les groupes de la box locale : refusé.
        agreement(List.of("urgences"), List.of("*"), 2);
        HttpResponse<String> outside = deposit(messages, foreign, foreignKeys, envelope, "application/octet-stream");
        assertThat(outside.statusCode()).isEqualTo(403);
        assertThat(outside.body()).contains("hors de l'accord");
        // Box du partenaire hors de son périmètre : inconnue du relais.
        agreement(List.of("*"), List.of("neurochirurgie"), 3);
        assertThat(deposit(messages, foreign, foreignKeys, envelope, "application/octet-stream").statusCode())
                .isEqualTo(401);
    }

    @Test
    void theRelayReadsRecognitionsAndPartnerEntriesFromItsDirectory() throws Exception {
        Instant now = Instant.now();
        SignedDocument recognition = RelayFixture.ORGANIZATION.recognize(partner, new Recognition.Scope(List.of("*"),
                List.of("*")), now, Duration.ofDays(90));
        UUID other = UUID.randomUUID();
        SignedDocument entry = PARTNER.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), other, "Autre box",
                Map.of("iu", PARTNER_RELAY + "/iu/v1/queues/r"), now), now, Duration.ofDays(30), 1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/recognitions") ? "{\"organization\":\"chu-exemple\",\"recognitions\":["
                    + recognition.toJson() + "]}" : path.contains("/partners/")
                    ? "{\"organization\":\"bourgogne-fc\",\"entries\":[" + entry.toJson() + "]}"
                    : "{\"organization\":\"chu-exemple\",\"entries\":[]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            RelayConfiguration.synchronize(directory, new DirectoryClient(URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort()), Duration.ofSeconds(5)));
            assertThat(directory.entry(other)).isPresent();
            assertThat(directory.isLocal(other)).isFalse();
        } finally {
            server.stop(0);
        }
        assertThatCode(() -> RelayConfiguration.synchronize(null, null)).as("erreur imprévue journalisée")
                .doesNotThrowAnyException();
    }
}
