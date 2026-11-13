package com.imagunity.directoryserver;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.CentralConfiguration;
import com.imagunity.directory.ConfigurationReport;
import com.imagunity.directory.EnrollmentRequest;
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
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Configuration centralisée sur le service d'annuaire, par HTTP réel (données synthétiques). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConfigurationApiTest {

    private static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    private static final Organization ORGANIZATION = new Organization("chu-exemple", ORG_KEYS);
    private static final String ACCOUNT = "responsable:secret-de-test";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            Path key = Files.createTempDirectory("annuaire").resolve("organisation.pub.json");
            KeyFiles.writeOrganizationPublic(key, "chu-exemple", ORG_KEYS.publicKey());
            registry.add("imagunity.directory.organization-key", key::toString);
            registry.add("spring.datasource.url", () -> "jdbc:h2:mem:configuration-" + UUID.randomUUID()
                    + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String body, String account, boolean adminHeader)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                        + environment.getProperty("local.server.port") + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (account != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(account.getBytes(StandardCharsets.UTF_8)));
        }
        if (adminHeader) {
            request.header("X-ImagUnity-Admin", "1");
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static CentralConfiguration configuration(long sequence, CentralConfiguration.Target target,
                                                      String modality) {
        return CentralConfiguration.draft("chu-exemple", target, sequence, Instant.now(), "responsable", "essai",
                new CentralConfiguration.Priority(5, List.of(new CentralConfiguration.Rule("thorax", 1,
                        Map.of("Modality", modality), null, null))),
                new CentralConfiguration.Routing(List.of(new CentralConfiguration.Rule("chu", null,
                        Map.of("InstitutionName", "CHU*"), null, List.of("box-b")))));
    }

    @Test
    void signedConfigurationsArePublishedWithGrowingNumbers() throws Exception {
        SignedDocument first = ORGANIZATION.signConfiguration(configuration(100, CentralConfiguration.Target
                .everyone(), "CT"));
        String path = "/directory/v1/configurations";
        assertThat(send("POST", path, first.toJson(), null, false).statusCode()).as("sans compte").isEqualTo(401);
        assertThat(send("POST", path, first.toJson(), ACCOUNT, false).statusCode()).isEqualTo(201);
        assertThat(send("POST", path, first.toJson(), ACCOUNT, false).statusCode()).as("idempotent").isEqualTo(200);
        SignedDocument other = ORGANIZATION.signConfiguration(configuration(100, CentralConfiguration.Target
                .everyone(), "MR"));
        HttpResponse<String> conflict = send("POST", path, other.toJson(), ACCOUNT, false);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("croissant");
        SignedDocument forged = new Organization("chu-exemple", HybridKeyPair.generate())
                .signConfiguration(configuration(101, CentralConfiguration.Target.everyone(), "CT"));
        assertThat(send("POST", path, forged.toJson(), ACCOUNT, false).statusCode()).isEqualTo(422);
        SignedDocument unknownKeyword = ORGANIZATION.signConfiguration(CentralConfiguration.draft("chu-exemple",
                CentralConfiguration.Target.everyone(), 101, Instant.now(), null, null, new CentralConfiguration
                        .Priority(null, List.of(new CentralConfiguration.Rule("x", 1, Map.of("Modalite", "CT"), null,
                        null))), null));
        HttpResponse<String> invalid = send("POST", path, unknownKeyword.toJson(), ACCOUNT, false);
        assertThat(invalid.statusCode()).isEqualTo(422);
        assertThat(invalid.body()).contains("Règle invalide").contains("Modalite");
        assertThat(send("POST", path, "pas du json", ACCOUNT, false).statusCode()).isEqualTo(422);

        JsonNode listed = JSON.readTree(send("GET", path, null, null, false).body());
        assertThat(listed.get("organization").asString()).isEqualTo("chu-exemple");
        assertThat(listed.get("configurations")).isNotEmpty();
    }

    @Test
    void boxesReportWhatTheyApplyWithASignatureOfTheirOwn() throws Exception {
        HybridKeyPair boxKeys = HybridKeyPair.generate();
        UUID box = UUID.randomUUID();
        Instant now = Instant.now();
        SignedDocument entry = ORGANIZATION.enroll(EnrollmentRequest.create(boxKeys, box, "Site B", Map.of(), now),
                now.minusSeconds(60), Duration.ofDays(30), 1, List.of("imagerie-nord"));
        assertThat(send("POST", "/directory/v1/entries", entry.toJson(), ACCOUNT, false).statusCode()).isEqualTo(201);

        String reports = "/directory/v1/configurations/reports";
        SignedDocument report = ConfigurationReport.of(box, 3, now, 4, "règle invalide", now).sign(boxKeys);
        assertThat(send("POST", reports, report.toJson(), null, false).statusCode()).isEqualTo(201);
        SignedDocument older = ConfigurationReport.of(box, 2, now, 0, null, now.minusSeconds(30)).sign(boxKeys);
        assertThat(send("POST", reports, older.toJson(), null, false).statusCode()).as("plus ancien").isEqualTo(200);
        SignedDocument stranger = ConfigurationReport.of(UUID.randomUUID(), 1, now, 0, null, now).sign(boxKeys);
        assertThat(send("POST", reports, stranger.toJson(), null, false).statusCode()).isEqualTo(422);
        SignedDocument future = ConfigurationReport.of(box, 3, now, 0, null, now.plus(Duration.ofHours(1)))
                .sign(boxKeys);
        assertThat(send("POST", reports, future.toJson(), null, false).statusCode()).isEqualTo(422);
        assertThat(send("POST", reports, "x".repeat(70_000), null, false).statusCode()).isEqualTo(422);

        HttpResponse<String> state = send("GET", "/admin/api/state", null, ACCOUNT, false);
        assertThat(state.statusCode()).isEqualTo(200);
        JsonNode boxView = null;
        for (JsonNode b : JSON.readTree(state.body()).get("boxes")) {
            if (b.get("boxId").asString().equals(box.toString())) {
                boxView = b;
            }
        }
        assertThat(boxView).isNotNull();
        assertThat(boxView.get("applied").asLong()).isEqualTo(3);
        assertThat(boxView.get("refusal").asString()).isEqualTo("règle invalide");
        assertThat(boxView.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(boxView.get("groups").get(0).asString()).isEqualTo("imagerie-nord");
        assertThat(JSON.readTree(state.body()).get("groups").toString()).contains("imagerie-nord");

        String byGroup = "{\"target\": {\"all\": false, \"groups\": [\"imagerie-nord\"]}, "
                + "\"routing\": {\"rules\": [{\"name\": \"chu\", \"and\": {\"Modality\": \"CT\"}, "
                + "\"to\": [\"box-b\"]}]}}";
        HttpResponse<String> draft = send("POST", "/admin/api/draft", byGroup, ACCOUNT, true);
        assertThat(draft.statusCode()).isEqualTo(200);
        assertThat(CentralConfiguration.fromJson(draft.body()).target().groups()).containsExactly("imagerie-nord");
    }

    @Test
    void theInterfacePreparesDraftsAndTriesRules() throws Exception {
        assertThat(send("GET", "/admin/api/state", null, null, false).statusCode()).as("sans compte").isEqualTo(401);
        assertThat(send("GET", "/admin/", null, ACCOUNT, false).statusCode()).isEqualTo(302);
        HttpResponse<String> page = send("GET", "/admin/index.html", null, ACCOUNT, false);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Configuration des box");
        assertThat(page.headers().firstValue("Content-Security-Policy")).get().asString().contains("default-src 'self'");

        String draft = """
                {"target": {"all": true}, "author": " responsable ", "comment": "",
                 "priority": {"default": 4, "rules": [{"name": "thorax", "priority": 1,
                   "and": {"Modality": "CT", "SeriesDescription": "*THORAX*"}}]},
                 "routing": {"rules": [{"name": "chu", "and": {"InstitutionName": "CHU*"}, "to": ["box-b"]}]}}""";
        assertThat(send("POST", "/admin/api/draft", draft, ACCOUNT, false).statusCode()).as("sans en-tête")
                .isEqualTo(403);
        HttpResponse<String> prepared = send("POST", "/admin/api/draft", draft, ACCOUNT, true);
        assertThat(prepared.statusCode()).isEqualTo(200);
        CentralConfiguration parsed = CentralConfiguration.fromJson(prepared.body());
        assertThat(parsed.author()).isEqualTo("responsable");
        assertThat(parsed.comment()).isNull();
        assertThat(parsed.sequence()).isPositive();
        // Le responsable signe le brouillon téléchargé, puis le publie par l'interface.
        SignedDocument signed = ORGANIZATION.signConfiguration(parsed);
        assertThat(send("POST", "/admin/api/publish", signed.toJson(), ACCOUNT, true).statusCode()).isEqualTo(201);
        JsonNode state = JSON.readTree(send("GET", "/admin/api/state", null, ACCOUNT, false).body());
        assertThat(state.get("documents").get(0).get("sequence").asLong()).isEqualTo(parsed.sequence());
        assertThat(state.get("nextSequence").asLong()).isEqualTo(parsed.sequence() + 1);

        String bad = draft.replace("\"Modality\"", "\"Modalite\"");
        HttpResponse<String> refused = send("POST", "/admin/api/draft", bad, ACCOUNT, true);
        assertThat(refused.statusCode()).isEqualTo(422);
        assertThat(refused.body()).contains("Modalite");
        assertThat(send("POST", "/admin/api/draft", "{\"author\": \"x\"}", ACCOUNT, true).statusCode())
                .isEqualTo(422);

        String trial = "{\"draft\": " + draft + ", \"header\": {\"Modality\": \"CT\", \"SeriesDescription\": "
                + "\"THORAX 1.25\", \"InstitutionName\": \"CHU NORD\"}}";
        JsonNode result = JSON.readTree(send("POST", "/admin/api/trial", trial, ACCOUNT, true).body());
        assertThat(result.get("priority").asInt()).isEqualTo(1);
        assertThat(result.get("priorityRule").asString()).isEqualTo("thorax");
        assertThat(result.get("destinations").get(0).asString()).isEqualTo("box-b");
        HttpResponse<String> unknown = send("POST", "/admin/api/trial", "{\"draft\": " + draft
                + ", \"header\": {\"Inconnu\": \"x\"}}", ACCOUNT, true);
        assertThat(unknown.statusCode()).isEqualTo(422);
        JsonNode empty = JSON.readTree(send("POST", "/admin/api/trial", "{\"draft\": " + draft + "}", ACCOUNT, true)
                .body());
        assertThat(empty.get("priority").asInt()).isEqualTo(4);
    }
}
