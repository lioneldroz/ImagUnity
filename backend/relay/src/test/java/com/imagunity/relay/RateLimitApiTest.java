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
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Limites de débit du relais, par HTTP réel : dépôts par box, requêtes mal signées par adresse. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RateLimitApiTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        RelayFixture.start();
        registry.add("imagunity.relay.directory.url", RelayFixture::directoryUrl);
        registry.add("imagunity.relay.directory.organization-key", () -> RelayFixture.organizationKey.toString());
        registry.add("imagunity.relay.directory.organization-code",
                () -> RelayFixture.ORG_KEYS.publicKey().shortCode());
        registry.add("imagunity.relay.storage-dir", () -> "./target/morceaux-" + UUID.randomUUID());
        registry.add("imagunity.relay.limits.deposits-per-minute", () -> "4");
        registry.add("imagunity.relay.limits.failed-per-minute", () -> "2");
        // Comme en production (application.yml du relais) : adresse du client prise dans X-Forwarded-For.
        registry.add("server.forward-headers-strategy", () -> "native");
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:limites-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private Environment environment;
    @Autowired
    private LocalDirectory directory;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> deposit(String path, UUID box, HybridKeyPair keys, byte[] body, String type,
                                         String forwardedFor) throws Exception {
        SignedRequest signed = SignedRequest.of("POST", path, body, Instant.now());
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getProperty("local.server.port") + path)).header("Content-Type", type)
                .header(SignedRequest.KEY_ID, box.toString())
                .header(SignedRequest.TIMESTAMP, Long.toString(signed.timestamp()))
                .header(SignedRequest.REQUEST_ID, signed.requestId())
                .header(SignedRequest.SIGNATURE, signed.signDeposit(keys))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void depositsPerBoxAndBadSignaturesPerAddressAreLimited() throws Exception {
        for (int i = 0; i < 100 && directory.entry(RelayFixture.ACTIVE).isEmpty(); i++) {
            Thread.sleep(50);
        }
        byte[] queue = ("{\"suite\":\"IU1\",\"queueKey\":\"" + Codec.base64Url(KeyPairGenerator.getInstance("Ed25519")
                .generateKeyPair().getPublic().getEncoded()) + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpResponse<String> created = deposit("/iu/v1/queues", RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS, queue,
                "application/json", null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String messages = "/iu/v1/queues/" + Codec.json().readTree(created.body()).get("sendId").asString()
                + "/messages";
        byte[] envelope = "enveloppe".getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < 3; i++) {
            assertThat(deposit(messages, RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS, envelope,
                    "application/octet-stream", null).statusCode()).isEqualTo(202);
        }
        HttpResponse<String> limited = deposit(messages, RelayFixture.ACTIVE, RelayFixture.ACTIVE_KEYS, envelope,
                "application/octet-stream", null);
        assertThat(limited.statusCode()).isEqualTo(429);
        assertThat(limited.headers().firstValue("Retry-After")).hasValueSatisfying(v -> assertThat(Long.parseLong(v))
                .isPositive());
        assertThat(limited.body()).contains("Trop de requêtes de cette box");

        // Signatures fausses depuis une adresse (derrière le répartiteur) : refusée ensuite avant vérification ;
        // les autres adresses ne sont pas touchées.
        HybridKeyPair wrong = HybridKeyPair.generate();
        for (int i = 0; i < 2; i++) {
            assertThat(deposit(messages, RelayFixture.ACTIVE, wrong, envelope, "application/octet-stream",
                    "203.0.113.7").statusCode()).isEqualTo(401);
        }
        HttpResponse<String> blocked = deposit(messages, RelayFixture.ACTIVE, wrong, envelope,
                "application/octet-stream", "203.0.113.7");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.body()).contains("mal signées");
        assertThat(deposit(messages, UUID.randomUUID(), wrong, envelope, "application/octet-stream", "203.0.113.8")
                .statusCode()).as("autre adresse").isEqualTo(401);
        assertThat(deposit(messages, RelayFixture.ACTIVE, wrong, envelope, "application/octet-stream",
                "203.0.113.8").statusCode()).isEqualTo(401);
        assertThat(deposit(messages, RelayFixture.ACTIVE, wrong, envelope, "application/octet-stream",
                "203.0.113.8").statusCode()).as("identifiant mal formé compté aussi").isEqualTo(429);
    }
}
