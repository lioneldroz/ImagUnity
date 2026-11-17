package com.imagunity.directoryserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.Freshness;
import com.imagunity.directory.FreshnessProof;
import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.Organization;
import com.imagunity.directory.Recognition;
import com.imagunity.directory.SignedDocument;
import com.imagunity.directory.SignedRead;
import java.io.IOException;
import java.io.UncheckedIOException;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Étape 4 de la fédération : lecture réservée et lecture par différence, côté service d'annuaire. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RestrictedReadApiTest {

    private static final HybridKeyPair A_KEYS = HybridKeyPair.generate();
    private static final Organization A = new Organization("grand-est", A_KEYS);
    private static final Organization B = new Organization("bourgogne-fc", HybridKeyPair.generate());
    private static final String PUBLISHER = "responsable:secret-de-test";
    private static final String READER = "lecteur:lecture-de-test";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            Path dir = Files.createTempDirectory("annuaire");
            Path key = dir.resolve("organisation.pub.json");
            KeyFiles.writeOrganizationPublic(key, A.id(), A_KEYS.publicKey());
            registry.add("imagunity.directory.organization-key", key::toString);
            registry.add("imagunity.directory.organization-code", () -> A_KEYS.publicKey().shortCode());
            registry.add("imagunity.directory.restricted-read", () -> "true");
            registry.add("imagunity.directory.reader-password", () -> "lecture-de-test");
            registry.add("imagunity.directory.partner-sync-interval", () -> "1h");
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:lecture-" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private Environment environment;
    @Autowired
    private RecognitionStore store;

    private final HttpClient http = HttpClient.newHttpClient();

    private String base() {
        return "http://127.0.0.1:" + environment.getProperty("local.server.port");
    }

    private HttpResponse<String> get(String path, Map<String, String> headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path));
        headers.forEach(request::header);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, Map.of());
    }

    private static Map<String, String> basic(String account) {
        return Map.of("Authorization", "Basic " + Base64.getEncoder().encodeToString(account.getBytes(
                StandardCharsets.UTF_8)));
    }

    private HttpResponse<String> signed(String path, String reader, HybridKeyPair keys) throws Exception {
        return get(path, new SignedRead(reader, keys).headers(path, Instant.now()));
    }

    private int publish(SignedDocument document, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json")
                .headers("Authorization", basic(PUBLISHER).get("Authorization"))
                .POST(HttpRequest.BodyPublishers.ofString(document.toJson())).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private SignedDocument enroll(HybridKeyPair keys, UUID box, String name) {
        return A.enroll(EnrollmentRequest.create(keys, HybridKemKeyPair.generate().publicKey(), box, name,
                Map.of("iu", "https://relais.a.example/iu/v1/queues/q"), Instant.now()), Instant.now(),
                Duration.ofDays(90), 1, List.of(), name);
    }

    @Test
    void encodedPathsDoNotBypassTheRestriction() throws Exception {
        assertThat(publish(enroll(HybridKeyPair.generate(), UUID.randomUUID(), "Box A"), "/directory/v1/entries"))
                .isEqualTo(201);
        // Lettres encodées : décodées par le routage, elles ne doivent pas tromper le filtre.
        for (String path : List.of("/directory/v1/%65ntries", "/directory/v1/%72ecognitions",
                "/directory/v1/%63onfigurations", "/directory/%761/entries")) {
            assertThat(get(path).statusCode()).as(path).isNotEqualTo(200);
        }
    }

    @Test
    void listsAreReservedToAccountsActiveBoxesAndRecognizedPartners() throws Exception {
        HybridKeyPair boxKeys = HybridKeyPair.generate();
        UUID box = UUID.randomUUID();
        SignedDocument entry = enroll(boxKeys, box, "Box A");
        assertThat(publish(entry, "/directory/v1/entries")).isEqualTo(201);

        // Publics : organisation, preuve de fraîcheur (404 ici, sans clé), entrée d'une box donnée.
        assertThat(get("/directory/v1/organization").statusCode()).isEqualTo(200);
        assertThat(get("/directory/v1/freshness").statusCode()).isEqualTo(404);
        assertThat(get("/directory/v1/entries/" + box).statusCode()).isEqualTo(200);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);

        // Réservés.
        HttpResponse<String> anonymous = get("/directory/v1/entries");
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(v -> assertThat(v)
                .startsWith("Basic"));
        for (String path : List.of("/directory/v1/recognitions", "/directory/v1/configurations",
                "/directory/v1/entries/" + box + "/history", "/directory/v1/partners/" + "0".repeat(64) + "/entries")) {
            assertThat(get(path).statusCode()).as(path).isEqualTo(401);
        }
        assertThat(get("/directory/v1/entries", basic("responsable:faux")).statusCode()).isEqualTo(401);
        assertThat(get("/directory/v1/entries", basic(PUBLISHER)).statusCode()).isEqualTo(200);
        assertThat(get("/directory/v1/entries/" + box + "/history", basic(READER)).statusCode()).isEqualTo(200);
        assertThat(get("/directory/v1/entries", basic(READER)).body()).contains(box.toString());

        // Box active : lecture signée avec la clé de son entrée.
        assertThat(signed("/directory/v1/entries", box.toString(), boxKeys).statusCode()).isEqualTo(200);
        assertThat(signed("/directory/v1/recognitions", box.toString(), boxKeys).statusCode()).isEqualTo(200);
        assertThat(signed("/directory/v1/configurations", box.toString(), boxKeys).statusCode()).isEqualTo(200);
        assertThat(signed("/directory/v1/entries/" + box + "/history", box.toString(), boxKeys).statusCode())
                .as("historique : comptes seulement").isEqualTo(401);
        assertThat(signed("/directory/v1/entries", box.toString(), HybridKeyPair.generate()).statusCode())
                .as("autre clé").isEqualTo(401);
        assertThat(signed("/directory/v1/entries", UUID.randomUUID().toString(), boxKeys).statusCode())
                .as("box inconnue").isEqualTo(401);
        assertThat(signed("/directory/v1/entries", "pas-un-uuid", boxKeys).statusCode()).isEqualTo(401);
        Map<String, String> replayed = new SignedRead(box.toString(), boxKeys).headers("/directory/v1/entries",
                Instant.now().minus(Duration.ofMinutes(10)));
        assertThat(get("/directory/v1/entries", replayed).statusCode()).as("lecture trop ancienne").isEqualTo(401);
        Map<String, String> other = new SignedRead(box.toString(), boxKeys).headers("/directory/v1/recognitions",
                Instant.now());
        assertThat(get("/directory/v1/entries", other).statusCode()).as("signée pour un autre chemin").isEqualTo(401);

        // Box révoquée : plus de lecture.
        HybridKeyPair revokedKeys = HybridKeyPair.generate();
        UUID revoked = UUID.randomUUID();
        SignedDocument revokedEntry = enroll(revokedKeys, revoked, "Box révoquée");
        assertThat(publish(revokedEntry, "/directory/v1/entries")).isEqualTo(201);
        assertThat(signed("/directory/v1/entries", revoked.toString(), revokedKeys).statusCode()).isEqualTo(200);
        assertThat(publish(A.revoke(revokedEntry, Instant.now(), "essai"), "/directory/v1/entries")).isEqualTo(201);
        assertThat(signed("/directory/v1/entries", revoked.toString(), revokedKeys).statusCode()).isEqualTo(401);

        // Service d'annuaire d'un partenaire reconnu : clé de fraîcheur déléguée, liste des entrées seulement.
        HybridKeyPair freshB = HybridKeyPair.generate();
        String fpB = B.publicKey().fingerprint();
        String readerB = SignedRead.ORGANIZATION + fpB;
        assertThat(signed("/directory/v1/entries", readerB, freshB).statusCode()).as("pas encore reconnu")
                .isEqualTo(401);
        Recognition.Partner partner = new Recognition.Partner(B.id(), B.publicKey().encoded(), "https://b.example",
                List.of("https://relais.b.example"));
        assertThat(publish(A.recognize(partner, Recognition.Scope.none(), Instant.now(), Duration.ofDays(90)),
                "/directory/v1/recognitions")).isEqualTo(201);
        assertThat(signed("/directory/v1/entries", readerB, freshB).statusCode()).as("reconnu, sans preuve relayée")
                .isEqualTo(401);
        Freshness proofB = new Freshness(B.delegateFreshness(freshB.publicKey(), Instant.now().minusSeconds(60),
                Duration.ofDays(5), 1), FreshnessProof.sign(freshB, B.id(), Instant.now(), Map.of(), Map.of()));
        SignedDocument entryB = B.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), UUID.randomUUID(), "Nord",
                Map.of(), Instant.now()), Instant.now(), Duration.ofDays(90), 1);
        List<List<SignedDocument>> served = new java.util.ArrayList<>(List.of(List.of(entryB)));
        FederationService federation = new FederationService(store, A.id(), A_KEYS.publicKey(), Clock.systemUTC(),
                uri -> served.getFirst(), uri -> Optional.of(proofB));
        federation.refreshPartners();
        assertThat(signed("/directory/v1/entries", readerB, freshB).statusCode()).isEqualTo(200);
        assertThat(signed("/directory/v1/entries?since=0", readerB, freshB).statusCode()).isEqualTo(200);
        assertThat(signed("/directory/v1/recognitions", readerB, freshB).statusCode()).as("liste des entrées seulement")
                .isEqualTo(401);
        assertThat(signed("/directory/v1/entries", readerB, HybridKeyPair.generate()).statusCode()).isEqualTo(401);
        assertThat(signed("/directory/v1/entries", SignedRead.ORGANIZATION + "court", freshB).statusCode())
                .isEqualTo(401);
        assertThat(federation.partnerReadKey("0".repeat(64))).isEmpty();

        // Entrées relayées du partenaire, lues par différence par une box.
        String partnerPath = "/directory/v1/partners/" + fpB + "/entries";
        HttpResponse<String> first = signed(partnerPath + "?since=0", box.toString(), boxKeys);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("\"cursor\"").contains("bourgogne-fc");
        long cursor = Long.parseLong(first.body().replaceAll(".*\"cursor\":(\\d+).*", "$1"));
        assertThat(signed(partnerPath + "?since=" + cursor, box.toString(), boxKeys).body())
                .contains("\"entries\":[]");
        assertThat(signed(partnerPath, box.toString(), boxKeys).body()).doesNotContain("cursor");
        served.set(0, List.of(B.renew(entryB, Instant.now(), Duration.ofDays(90))));
        federation.refreshPartners();
        assertThat(signed(partnerPath + "?since=" + cursor, box.toString(), boxKeys).body())
                .contains("\"entries\":[{");
    }

    @Test
    void entriesAreReadByDifferenceWithTheClient() throws Exception {
        HybridKeyPair keys = HybridKeyPair.generate();
        UUID box = UUID.randomUUID();
        DirectoryClient client = new DirectoryClient(URI.create(base()), Duration.ofSeconds(5))
                .readAs(new SignedRead(box.toString(), keys));
        assertThat(client.fetch()).as("pas encore enrôlée, pas d'entrée").isEmpty();
        SignedDocument entry = enroll(keys, box, "Box diff");
        assertThat(publish(entry, "/directory/v1/entries")).isEqualTo(201);
        assertThat(client.fetch()).as("enrôlée : lecture complète").contains(entry);
        assertThat(client.fetch()).as("rien de nouveau").isEmpty();
        SignedDocument added = enroll(HybridKeyPair.generate(), UUID.randomUUID(), "Box ajoutée");
        assertThat(publish(added, "/directory/v1/entries")).isEqualTo(201);
        assertThat(client.fetch()).containsExactly(added);
        client.resetCursors();
        assertThat(client.fetch()).contains(entry, added);
        assertThat(get("/directory/v1/entries?since=-5", basic(READER)).body()).contains(box.toString());
        assertThat(get("/directory/v1/entries", basic(READER)).body()).doesNotContain("cursor");
    }
}
