package com.imagunity.directoryserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.CentralConfiguration;
import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SignedDocument;
import com.imagunity.directory.TestRequest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Demandes d'essai de connexion et flux de réveil des box, par HTTP réel (données synthétiques). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestRequestApiTest {

    private static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    private static final Organization ORGANIZATION = new Organization("chu-exemple", ORG_KEYS);
    private static final String ACCOUNT = "responsable:secret-de-test";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            Path key = Files.createTempDirectory("annuaire").resolve("organisation.pub.json");
            KeyFiles.writeOrganizationPublic(key, "chu-exemple", ORG_KEYS.publicKey());
            registry.add("imagunity.directory.organization-key", key::toString);
            registry.add("imagunity.directory.organization-code", () -> ORG_KEYS.publicKey().shortCode());
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:essais-" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private Environment environment;
    @Autowired
    private DataSource dataSource;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base() {
        return "http://127.0.0.1:" + environment.getProperty("local.server.port");
    }

    private HttpResponse<String> send(String method, String path, String body, String account) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (account != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(account.getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aSignedRequestIsKeptAndWakesTheBoxesUp() throws Exception {
        UUID box = UUID.randomUUID();
        Instant now = Instant.now();
        SignedDocument entry = ORGANIZATION.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), box, "Box Nord",
                Map.of(), now), now.minusSeconds(60), Duration.ofDays(30), 1, List.of());
        assertThat(send("POST", "/directory/v1/entries", entry.toJson(), ACCOUNT).statusCode()).isEqualTo(201);

        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        Thread listener = Thread.ofVirtual().start(() -> {
            try {
                new DirectoryClient(URI.create(base()), Duration.ofSeconds(5)).listen((e, d) -> events.add(e + ":" + d));
            } catch (RuntimeException ignored) {
                // flux fermé à la fin du test
            }
        });
        Thread.sleep(1500); // flux ouvert, relevé initial fait

        String path = "/directory/v1/test-requests";
        SignedDocument request = ORGANIZATION.signTestRequest(box, Instant.now());
        assertThat(send("POST", path, request.toJson(), null).statusCode()).as("sans compte").isEqualTo(401);
        assertThat(send("POST", path, request.toJson(), ACCOUNT).statusCode()).isEqualTo(201);
        assertThat(await(events, "essai:" + box)).isEqualTo("essai:" + box);

        HttpResponse<String> kept = send("GET", path + "/" + box, null, null);
        assertThat(kept.statusCode()).isEqualTo(200);
        assertThat(TestRequest.verify(SignedDocument.fromJson(kept.body()), "chu-exemple", ORG_KEYS.publicKey(),
                Instant.now()).boxId()).isEqualTo(box);
        assertThat(send("GET", path + "/" + UUID.randomUUID(), null, null).statusCode()).isEqualTo(404);

        SignedDocument forged = new Organization("chu-exemple", HybridKeyPair.generate()).signTestRequest(box,
                Instant.now());
        assertThat(send("POST", path, forged.toJson(), ACCOUNT).body()).contains("non signée par le responsable");
        assertThat(send("POST", path, ORGANIZATION.signTestRequest(box, Instant.now().minus(Duration.ofHours(1)))
                .toJson(), ACCOUNT).body()).contains("trop ancienne");
        assertThat(send("POST", path, ORGANIZATION.signTestRequest(UUID.randomUUID(), Instant.now()).toJson(),
                ACCOUNT).body()).contains("absente de l'annuaire");
        assertThat(send("POST", path, "pas du json", ACCOUNT).statusCode()).isEqualTo(422);
        assertThat(send("POST", path, "x".repeat(TestRequestController.MAX_REQUEST + 1), ACCOUNT).body())
                .contains("trop longue");

        assertThat(send("POST", "/directory/v1/configurations", ORGANIZATION.signConfiguration(CentralConfiguration
                .draft("chu-exemple", CentralConfiguration.Target.everyone(), 1, Instant.now(), null, null,
                        new CentralConfiguration.Priority(3, List.of()), null)).toJson(), ACCOUNT).statusCode())
                .isEqualTo(201);
        assertThat(await(events, "publication:configurations")).isEqualTo("publication:configurations");
        listener.interrupt();
    }

    /** Premier événement attendu, les autres (réveils d'autres publications) étant ignorés ; null au bout de 10 s. */
    private static String await(BlockingQueue<String> events, String expected) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String event;
        do {
            event = events.poll(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
        } while (event != null && !event.equals(expected));
        return event;
    }

    @Test
    void tooManyListenersAreRefused() {
        DirectoryEvents full = new DirectoryEvents(dataSource, 0);
        assertThatThrownBy(full::subscribe).isInstanceOf(DirectoryEvents.TooManyListeners.class);
        DirectoryEvents events = new DirectoryEvents(dataSource, 2);
        events.subscribe();
        assertThat(events.listeners()).isEqualTo(1);
        events.heartbeat();
        events.start();
        events.testRequested(UUID.randomUUID());
        assertThat(events.isRunning()).isTrue();
        events.stop();
        assertThat(events.isRunning()).isFalse();
        assertThat(events.listeners()).isZero();
    }
}
