package com.imagunity.directoryserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.Organization;
import com.imagunity.directory.Recognition;
import com.imagunity.directory.SignedDocument;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Fédération côté service d'annuaire : reconnaissances et relais des entrées des partenaires (données synthétiques). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FederationApiTest {

    private static final HybridKeyPair A_KEYS = HybridKeyPair.generate();
    private static final Organization A = new Organization("grand-est", A_KEYS);
    private static final Organization B = new Organization("bourgogne-fc", HybridKeyPair.generate());
    private static final String ACCOUNT = "responsable:secret-de-test";
    private static final String RELAY_B = "https://relais.bourgogne-fc.example";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            Path key = Files.createTempDirectory("annuaire").resolve("organisation.pub.json");
            KeyFiles.writeOrganizationPublic(key, A.id(), A_KEYS.publicKey());
            registry.add("imagunity.directory.organization-key", key::toString);
            registry.add("imagunity.directory.organization-code", () -> A_KEYS.publicKey().shortCode());
            registry.add("imagunity.directory.partner-sync-interval", () -> "1h");
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:federation-" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private Environment environment;

    @Autowired
    private FederationService federation;

    @Autowired
    private RecognitionStore store;

    private final HttpClient http = HttpClient.newHttpClient();

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path);
    }

    private HttpResponse<String> post(String path, String body, String account) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (account != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(account.getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Recognition.Partner partner(String directory) {
        return new Recognition.Partner(B.id(), B.publicKey().encoded(), directory, List.of(RELAY_B));
    }

    private static SignedDocument entryOfB(UUID box, long serial) {
        Instant now = Instant.now();
        return B.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), HybridKemKeyPair.generate().publicKey(),
                box, "Box de Bourgogne", Map.of("iu", RELAY_B + "/iu/v1/queues/q"), now), now, Duration.ofDays(90),
                serial);
    }

    @Test
    void recognitionsArePublishedByTheResponsibleAndPartnerEntriesRelayed() throws Exception {
        // Annuaire simulé de l'organisation partenaire.
        List<String> entriesOfB = new CopyOnWriteArrayList<>();
        HttpServer partnerDirectory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        partnerDirectory.createContext("/directory/v1/entries", exchange -> {
            byte[] body = ("{\"organization\":\"bourgogne-fc\",\"entries\":[" + String.join(",", entriesOfB) + "]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        partnerDirectory.start();
        String directoryB = "http://127.0.0.1:" + partnerDirectory.getAddress().getPort();
        String fpB = B.publicKey().fingerprint();
        try {
            SignedDocument recognition = A.recognize(partner(directoryB), new Recognition.Scope(List.of("*"),
                    List.of("*")), Instant.now(), Duration.ofDays(90));
            assertThat(post("/directory/v1/recognitions", recognition.toJson(), null).statusCode()).isEqualTo(401);
            assertThat(post("/directory/v1/recognitions", recognition.toJson(), ACCOUNT).statusCode()).isEqualTo(201);
            assertThat(post("/directory/v1/recognitions", recognition.toJson(), ACCOUNT).statusCode()).isEqualTo(200);
            assertThat(get("/directory/v1/partners/" + fpB + "/entries").statusCode()).as("rien encore relayé")
                    .isEqualTo(200);

            JsonNode listed = JsonMapper.builder().build().readTree(get("/directory/v1/recognitions").body());
            assertThat(listed.get("organization").asString()).isEqualTo("grand-est");
            assertThat(listed.get("recognitions").valueStream().map(n -> SignedDocument.fromJson(n.toString())))
                    .as("l'autre test peut en avoir publié une").contains(recognition);

            // Relais : seules les entrées signées par la clé du partenaire sont gardées, la plus récente par box.
            UUID nord = UUID.randomUUID();
            entriesOfB.add(entryOfB(nord, 1).toJson());
            entriesOfB.add(new Organization("bourgogne-fc", HybridKeyPair.generate()).enroll(EnrollmentRequest.create(
                    HybridKeyPair.generate(), UUID.randomUUID(), "Intruse", Map.of(), Instant.now()), Instant.now(),
                    Duration.ofDays(90), 1).toJson());
            federation.refreshPartners();
            SignedDocument second = entryOfB(nord, 2);
            entriesOfB.set(0, second.toJson());
            federation.refreshPartners();
            entriesOfB.set(0, entryOfB(nord, 1).toJson());
            federation.refreshPartners();
            HttpResponse<String> relayed = get("/directory/v1/partners/" + fpB + "/entries");
            assertThat(relayed.statusCode()).isEqualTo(200);
            JsonNode body = JsonMapper.builder().build().readTree(relayed.body());
            assertThat(body.get("organization").asString()).isEqualTo("bourgogne-fc");
            assertThat(body.get("entries").size()).isEqualTo(1);
            assertThat(SignedDocument.fromJson(body.get("entries").get(0).toString())).isEqualTo(second);

            // Conflits et refus.
            SignedDocument twin = A.recognize(partner(directoryB), Recognition.Scope.none(), Instant.now(),
                    Duration.ofDays(90));
            assertThat(post("/directory/v1/recognitions", twin.toJson(), ACCOUNT).statusCode()).isEqualTo(409);
            assertThat(post("/directory/v1/recognitions", B.recognize(new Recognition.Partner(A.id(),
                    A.publicKey().encoded(), directoryB, List.of(RELAY_B)), Recognition.Scope.none(), Instant.now(),
                    Duration.ofDays(90)).toJson(), ACCOUNT).statusCode()).as("signée par une autre clé").isEqualTo(422);
            assertThat(post("/directory/v1/recognitions", "pas du json", ACCOUNT).statusCode()).isEqualTo(422);
            assertThat(get("/directory/v1/partners/inconnu/entries").statusCode()).isEqualTo(404);

            // Révocation : plus aucune entrée relayée n'est servie, ni relue.
            SignedDocument revoked = A.revokeRecognition(recognition, Instant.now(), "fin du partenariat");
            assertThat(post("/directory/v1/recognitions", revoked.toJson(), ACCOUNT).statusCode()).isEqualTo(201);
            assertThat(post("/directory/v1/recognitions", recognition.toJson(), ACCOUNT).statusCode())
                    .as("retour en arrière").isEqualTo(409);
            assertThat(get("/directory/v1/partners/" + fpB + "/entries").statusCode()).isEqualTo(404);
            federation.refreshPartners();
        } finally {
            partnerDirectory.stop(0);
        }
    }

    @Test
    void anUnreachablePartnerDirectoryIsOnlyLogged() {
        UUID nord = UUID.randomUUID();
        int[] calls = {0};
        FederationService service = new FederationService(store, A.id(), A.publicKey(), Clock.systemUTC(), uri -> {
            calls[0]++;
            if (uri.getHost().equals("panne.example")) {
                throw new DirectoryException("injoignable");
            }
            return List.of(entryOfB(nord, 1));
        });
        HybridKeyPair c = HybridKeyPair.generate();
        Organization other = new Organization("autre", c);
        service.publish(A.recognize(new Recognition.Partner(other.id(), other.publicKey().encoded(),
                "https://panne.example", List.of(RELAY_B)), Recognition.Scope.none(), Instant.now(),
                Duration.ofDays(1)));
        service.refreshPartners();
        assertThat(calls[0]).isGreaterThanOrEqualTo(1);
        assertThat(store.partnerEntries(other.publicKey().fingerprint())).isEmpty();
    }
}
