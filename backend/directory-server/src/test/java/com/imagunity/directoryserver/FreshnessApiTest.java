package com.imagunity.directoryserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.Freshness;
import com.imagunity.directory.FreshnessProof;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.Organization;
import com.imagunity.directory.Recognition;
import com.imagunity.directory.SignedDocument;
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

/** Preuves de fraîcheur côté service d'annuaire : publication et relais de celles des partenaires. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FreshnessApiTest {

    private static final HybridKeyPair A_KEYS = HybridKeyPair.generate();
    private static final Organization A = new Organization("grand-est", A_KEYS);
    private static final HybridKeyPair FRESH = HybridKeyPair.generate();
    private static final Organization B = new Organization("bourgogne-fc", HybridKeyPair.generate());
    private static final String ACCOUNT = "responsable:secret-de-test";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            Path dir = Files.createTempDirectory("annuaire");
            Path key = dir.resolve("organisation.pub.json");
            KeyFiles.writeOrganizationPublic(key, A.id(), A_KEYS.publicKey());
            Path freshKey = dir.resolve("fraicheur.json");
            KeyFiles.writePrivate(freshKey, FRESH, null);
            Path delegation = dir.resolve("delegation.json");
            Files.writeString(delegation, A.delegateFreshness(FRESH.publicKey(), Instant.now().minusSeconds(60),
                    Duration.ofDays(5), 1).toJson());
            registry.add("imagunity.directory.organization-key", key::toString);
            registry.add("imagunity.directory.organization-code", () -> A_KEYS.publicKey().shortCode());
            registry.add("imagunity.directory.freshness-key", freshKey::toString);
            registry.add("imagunity.directory.freshness-delegation", delegation::toString);
            registry.add("imagunity.directory.partner-sync-interval", () -> "1h");
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:fraicheur-" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private Environment environment;
    @Autowired
    private EntryStore entries;
    @Autowired
    private RecognitionStore store;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getProperty(
                "local.server.port") + path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getProperty(
                        "local.server.port") + path)).header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString(ACCOUNT.getBytes(
                        StandardCharsets.UTF_8))).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theDirectoryPublishesASignedProofThatFollowsItsEntries() throws Exception {
        HttpResponse<String> first = get("/directory/v1/freshness");
        assertThat(first.statusCode()).isEqualTo(200);
        FreshnessProof proof = Freshness.fromJson(first.body()).verify(A.id(), A_KEYS.publicKey(), Instant.now());
        assertThat(get("/directory/v1/freshness").body()).as("même état : même preuve").isEqualTo(first.body());

        UUID box = UUID.randomUUID();
        SignedDocument entry = A.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), box, "Box A", Map.of(),
                Instant.now()), Instant.now(), Duration.ofDays(90), 1);
        assertThat(post("/directory/v1/entries", entry.toJson()).statusCode()).isEqualTo(201);
        Recognition.Partner partner = new Recognition.Partner(B.id(), B.publicKey().encoded(), "https://b.example",
                List.of("https://relais.b.example"));
        assertThat(post("/directory/v1/recognitions", A.recognize(partner, Recognition.Scope.none(), Instant.now(),
                Duration.ofDays(90)).toJson()).statusCode()).isEqualTo(201);
        FreshnessProof after = Freshness.fromJson(get("/directory/v1/freshness").body()).verify(A.id(),
                A_KEYS.publicKey(), Instant.now());
        assertThat(after.entries()).containsEntry(box, 1L);
        assertThat(after.recognitions()).containsEntry(partner.fingerprint(), 1L);
        assertThat(after.issuedAt()).isAfterOrEqualTo(proof.issuedAt());

        // Preuve du partenaire relayée : vérifiée avec sa clé, la plus récente gardée, servie s'il est reconnu.
        assertThat(get("/directory/v1/partners/" + partner.fingerprint() + "/freshness").statusCode())
                .as("accord au périmètre vide mais actif : pas encore de preuve").isEqualTo(404);
        HybridKeyPair freshB = HybridKeyPair.generate();
        Freshness proofB = new Freshness(B.delegateFreshness(freshB.publicKey(), Instant.now().minusSeconds(60),
                Duration.ofDays(5), 1), FreshnessProof.sign(freshB, B.id(), Instant.now(), Map.of(), Map.of()));
        Freshness[] served = {proofB};
        FederationService federation = new FederationService(store, A.id(), A_KEYS.publicKey(), Clock.systemUTC(),
                uri -> List.of(), uri -> Optional.ofNullable(served[0]));
        federation.refreshPartners();
        HttpResponse<String> relayed = get("/directory/v1/partners/" + partner.fingerprint() + "/freshness");
        assertThat(relayed.statusCode()).isEqualTo(200);
        assertThat(Freshness.fromJson(relayed.body())).isEqualTo(proofB);
        served[0] = new Freshness(proofB.delegation(), FreshnessProof.sign(freshB, B.id(),
                Instant.now().minusSeconds(30), Map.of(), Map.of()));
        federation.refreshPartners();
        assertThat(Freshness.fromJson(get("/directory/v1/partners/" + partner.fingerprint() + "/freshness").body()))
                .as("plus ancienne : ignorée").isEqualTo(proofB);
        served[0] = new Freshness(proofB.delegation(), FreshnessProof.sign(HybridKeyPair.generate(), B.id(),
                Instant.now(), Map.of(), Map.of()));
        federation.refreshPartners();
        assertThat(Freshness.fromJson(get("/directory/v1/partners/" + partner.fingerprint() + "/freshness").body()))
                .as("falsifiée : écartée").isEqualTo(proofB);
        assertThat(get("/directory/v1/partners/inconnu/freshness").statusCode()).isEqualTo(404);
        assertThat(get("/directory/v1/partners/" + "0".repeat(64) + "/freshness").statusCode()).isEqualTo(404);
    }

    @Test
    void withoutAKeyThereIsNoProofAndABadDelegationIsRefused() {
        FreshnessService none = new FreshnessService(entries, store, A.id(), A_KEYS.publicKey(), null, null,
                Duration.ofMinutes(10), Clock.systemUTC());
        assertThat(none.current()).isEmpty();
        SignedDocument foreign = B.delegateFreshness(FRESH.publicKey(), Instant.now(), Duration.ofDays(5), 1);
        assertThatThrownBy(() -> new FreshnessService(entries, store, A.id(), A_KEYS.publicKey(), FRESH, foreign,
                Duration.ofMinutes(10), Clock.systemUTC())).hasMessageContaining("inutilisable");
        SignedDocument otherKey = A.delegateFreshness(HybridKeyPair.generate().publicKey(), Instant.now(),
                Duration.ofDays(5), 1);
        assertThatThrownBy(() -> new FreshnessService(entries, store, A.id(), A_KEYS.publicKey(), FRESH, otherKey,
                Duration.ofMinutes(10), Clock.systemUTC())).hasMessageContaining("clé déléguée");
        // Délégation longue : pas d'avertissement ; la preuve se re-signe après l'intervalle.
        FreshnessService service = new FreshnessService(entries, store, A.id(), A_KEYS.publicKey(), FRESH,
                A.delegateFreshness(FRESH.publicKey(), Instant.now().minusSeconds(5), Duration.ofDays(30), 2),
                Duration.ZERO, Clock.systemUTC());
        assertThat(service.current()).isPresent();
    }
}
