package com.imagunity.directoryserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.EnrollmentCode;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.EnrollmentSubmission;
import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.Organization;
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
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Demandes d'enrôlement envoyées par les box, par HTTP réel, sur H2 en mode PostgreSQL (données synthétiques). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EnrollmentApiTest {

    private static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    private static final Organization ORGANIZATION = new Organization("chu-exemple", ORG_KEYS);
    private static final String ACCOUNT = "responsable:secret-de-test";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            Path key = Files.createTempDirectory("annuaire").resolve("organisation.pub.json");
            KeyFiles.writeOrganizationPublic(key, "chu-exemple", ORG_KEYS.publicKey());
            registry.add("imagunity.directory.organization-key", key::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:enrolement-" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private Environment environment;

    @Autowired
    private javax.sql.DataSource dataSource;

    @Autowired
    private EntryStore entries;

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

    private HttpResponse<String> list(String account) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/directory/v1/enrollment-requests"));
        if (account != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(account.getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> refuse(UUID box, String account) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/directory/v1/enrollment-requests/" + box)).DELETE();
        if (account != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(account.getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static SignedDocument request(UUID box, String name) {
        return EnrollmentRequest.create(HybridKeyPair.generate(), HybridKemKeyPair.generate().publicKey(), box, name,
                Map.of(), Instant.now());
    }

    private HttpResponse<String> submit(EnrollmentSubmission submission) throws Exception {
        return post("/directory/v1/enrollment-requests", submission.toJson(), null);
    }

    @Test
    void boxesSubmitAndTheResponsibleReadsThePendingRequests() throws Exception {
        UUID box = UUID.randomUUID();
        String code = EnrollmentCode.generate();
        EnrollmentSubmission first = EnrollmentSubmission.of(request(box, "CHU Exemple — imagerie Nord"), code);
        assertThat(submit(first).statusCode()).as("sans compte").isEqualTo(202);
        // Nouvel envoi de la même box : remplace le précédent.
        EnrollmentSubmission again = EnrollmentSubmission.of(request(box, "CHU Exemple — imagerie Nord"), code);
        assertThat(submit(again).statusCode()).isEqualTo(202);
        UUID other = UUID.randomUUID();
        assertThat(submit(EnrollmentSubmission.of(request(other, "Clinique Exemple"), null)).statusCode())
                .isEqualTo(202);

        assertThat(list(null).statusCode()).isEqualTo(401);
        assertThat(list("responsable:faux").statusCode()).isEqualTo(401);
        HttpResponse<String> pending = list(ACCOUNT);
        assertThat(pending.statusCode()).isEqualTo(200);
        JsonNode demandes = JsonMapper.builder().build().readTree(pending.body());
        assertThat(demandes.size()).isEqualTo(2);
        JsonNode nord = demandes.valueStream().filter(d -> d.get("boxId").asString().equals(box.toString()))
                .findFirst().orElseThrow();
        assertThat(nord.get("withCode").asBoolean()).isTrue();
        assertThat(nord.get("displayName").asString()).isEqualTo("CHU Exemple — imagerie Nord");
        EnrollmentSubmission stored = EnrollmentSubmission.fromJson(nord.get("submission").toString());
        assertThat(stored).isEqualTo(again);
        assertThat(stored.provenBy(code)).as("transmise telle quelle").isTrue();

        // Entrée publiée : la demande n'attend plus, et une nouvelle demande de cette box est refusée.
        SignedDocument entry = ORGANIZATION.enroll(again.request(), Instant.now(), Duration.ofDays(90), 1);
        assertThat(post("/directory/v1/entries", entry.toJson(), ACCOUNT).statusCode()).isEqualTo(201);
        assertThat(list(ACCOUNT).body()).doesNotContain(box.toString()).contains(other.toString());
        HttpResponse<String> enrolled = submit(EnrollmentSubmission.of(request(box, "CHU Exemple"), code));
        assertThat(enrolled.statusCode()).isEqualTo(409);
        assertThat(enrolled.body()).contains("déjà enrôlée");
    }

    @Test
    void theResponsibleRefusesARequestAndTheBoxIsThenIgnored() throws Exception {
        UUID box = UUID.randomUUID();
        assertThat(submit(EnrollmentSubmission.of(request(box, "Box inconnue"), null)).statusCode()).isEqualTo(202);
        assertThat(refuse(box, null).statusCode()).as("sans compte").isEqualTo(401);
        assertThat(refuse(box, ACCOUNT).statusCode()).isEqualTo(204);
        assertThat(list(ACCOUNT).body()).doesNotContain(box.toString());
        assertThat(refuse(box, ACCOUNT).statusCode()).as("déjà refusée").isEqualTo(404);
        assertThat(refuse(UUID.randomUUID(), ACCOUNT).statusCode()).isEqualTo(404);

        HttpResponse<String> again = submit(EnrollmentSubmission.of(request(box, "Box inconnue"), null));
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("refusée par le responsable");
        assertThat(list(ACCOUNT).body()).doesNotContain(box.toString());

        // Trente et un jours plus tard, le refus est oublié : la box peut demander à nouveau.
        EnrollmentStore store = new EnrollmentStore(dataSource);
        EnrollmentService later = new EnrollmentService(store, entries, "chu-exemple", ORG_KEYS.publicKey(),
                Clock.fixed(Instant.now().plus(Duration.ofDays(31)), ZoneOffset.UTC));
        later.pending();
        assertThat(store.refused(box)).isFalse();
        assertThat(store.exists(box)).isFalse();
    }

    @Test
    void invalidRequestsAreRefused() throws Exception {
        SignedDocument good = request(UUID.randomUUID(), "Box d'essai");
        SignedDocument forged = new SignedDocument(good.payload(), request(UUID.randomUUID(), "x").signatures());

        assertThat(submit(EnrollmentSubmission.of(forged, null)).statusCode()).as("mal signée").isEqualTo(422);
        assertThat(post("/directory/v1/enrollment-requests", "pas du json", null).statusCode()).isEqualTo(422);
        assertThat(post("/directory/v1/enrollment-requests", "{\"x\":\"" + "a".repeat(70_000) + "\"}", null)
                .statusCode()).as("trop longue").isEqualTo(422);
        assertThat(submit(EnrollmentSubmission.of(request(UUID.randomUUID(), "x".repeat(257)), null)).body())
                .contains("trop long");
        assertThat(submit(EnrollmentSubmission.of(request(UUID.randomUUID(), " "), null)).statusCode())
                .isEqualTo(422);
    }

    @Test
    void pendingRequestsAreCappedAndForgottenAfterAWhile() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("DELETE FROM enrollment_request").update();
        EnrollmentStore store = new EnrollmentStore(dataSource);
        SignedDocument filler = request(UUID.randomUUID(), "Remplissage");
        Instant now = Instant.now();
        for (int i = 0; i < EnrollmentService.MAX_PENDING; i++) {
            store.save(UUID.randomUUID(), "Box " + i, "XXXX", EnrollmentSubmission.of(filler, null), now);
        }
        HttpResponse<String> full = submit(EnrollmentSubmission.of(request(UUID.randomUUID(), "Une de trop"), null));
        assertThat(full.statusCode()).isEqualTo(429);

        // Quinze jours plus tard, sans suite : effacées.
        EnrollmentService later = new EnrollmentService(store, entries, "chu-exemple", ORG_KEYS.publicKey(),
                Clock.fixed(now.plus(Duration.ofDays(15)), ZoneOffset.UTC));
        assertThat(later.pending()).isEmpty();
        assertThatThrownBy(() -> later.submit("{}")).isInstanceOf(PublicationService.InvalidEntryException.class);
    }
}
