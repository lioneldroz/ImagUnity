package com.imagunity.directoryserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Service d'annuaire par HTTP réel, sur H2 en mode PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DirectoryServerTest {

    private static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    private static final Organization ORGANIZATION = new Organization("chu-exemple", ORG_KEYS);
    private static final Instant NOW = Instant.now();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        try {
            Path key = Files.createTempDirectory("annuaire").resolve("organisation.pub.json");
            KeyFiles.writeOrganizationPublic(key, "chu-exemple", ORG_KEYS.publicKey());
            registry.add("imagunity.directory.organization-key", key::toString);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> publish(String body, String account) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri("/directory/v1/entries"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (account != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder()
                    .encodeToString(account.getBytes(StandardCharsets.UTF_8)));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port") + path);
    }

    private static SignedDocument enrolled(UUID box, String name, long serial) {
        SignedDocument request = EnrollmentRequest.create(HybridKeyPair.generate(), box, name,
                Map.of("simplex", "simplex:/contact#" + box), NOW);
        return ORGANIZATION.enroll(request, NOW, Duration.ofDays(30), serial);
    }

    @Test
    void publishesReadsAndRevokesEntries() throws Exception {
        UUID box = UUID.randomUUID();
        SignedDocument entry = enrolled(box, "CHU Exemple — site B", 1);

        assertThat(publish(entry.toJson(), "responsable:secret-de-test").statusCode()).isEqualTo(201);
        assertThat(publish(entry.toJson(), "responsable:secret-de-test").statusCode()).as("idempotent").isEqualTo(200);

        HttpResponse<String> current = get("/directory/v1/entries/" + box);
        assertThat(current.statusCode()).isEqualTo(200);
        assertThat(SignedDocument.fromJson(current.body())).isEqualTo(entry);
        assertThat(get("/directory/v1/entries").body()).contains("\"organization\":\"chu-exemple\"")
                .contains(box.toString().substring(0, 0) + "payload");

        SignedDocument revoked = ORGANIZATION.revoke(entry, NOW.plusSeconds(60), "Box volée");
        assertThat(publish(revoked.toJson(), "responsable:secret-de-test").statusCode()).isEqualTo(201);
        assertThat(SignedDocument.fromJson(get("/directory/v1/entries/" + box).body())).isEqualTo(revoked);
        assertThat(get("/directory/v1/entries/" + box + "/history").body()).contains("\\\"serial\\\":1")
                .contains("\\\"serial\\\":2");

        HttpResponse<String> rollback = publish(entry.toJson(), "responsable:secret-de-test");
        assertThat(rollback.statusCode()).isEqualTo(409);
        assertThat(rollback.body()).contains("Retour en arrière");
        HttpResponse<String> otherSameSerial = publish(enrolled(box, "Autre", 2).toJson(), "responsable:secret-de-test");
        assertThat(otherSameSerial.statusCode()).isEqualTo(409);
        assertThat(otherSameSerial.body()).contains("Une autre entrée n° 2");
    }

    @Test
    void invalidEntriesAreRefused() throws Exception {
        SignedDocument foreign = new Organization("chu-exemple", HybridKeyPair.generate()).enroll(
                EnrollmentRequest.create(HybridKeyPair.generate(), UUID.randomUUID(), "Intrus", Map.of(), NOW), NOW,
                Duration.ofDays(30), 1);

        HttpResponse<String> forged = publish(foreign.toJson(), "responsable:secret-de-test");
        HttpResponse<String> garbage = publish("pas du json", "responsable:secret-de-test");

        assertThat(forged.statusCode()).isEqualTo(422);
        assertThat(forged.body()).contains("non signée par le responsable");
        assertThat(garbage.statusCode()).isEqualTo(422);
        assertThat(garbage.body()).contains("illisible");
    }

    @Test
    void onlyThePublisherCanPublish() throws Exception {
        String entry = enrolled(UUID.randomUUID(), "B", 1).toJson();

        assertThat(publish(entry, null).statusCode()).isEqualTo(401);
        assertThat(publish(entry, "responsable:faux").statusCode()).isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(uri("/directory/v1/entries/x")).DELETE().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).isIn(401, 403);
    }

    @Test
    void readsAreOpen() throws Exception {
        HttpResponse<String> organization = get("/directory/v1/organization");

        assertThat(organization.statusCode()).isEqualTo(200);
        assertThat(organization.body()).contains(ORG_KEYS.publicKey().shortCode());
        assertThat(get("/directory/v1/entries/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(get("/directory/v1/entries/" + UUID.randomUUID() + "/history").statusCode()).isEqualTo(404);
        assertThat(get("/actuator/health").statusCode()).isEqualTo(200);
    }

    @Test
    void configurationChecks() {
        DirectoryConfiguration configuration = new DirectoryConfiguration();
        var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

        assertThatThrownBy(() -> configuration.organizationKey(new DirectoryServerProperties(null, "r", "p")))
                .hasMessageContaining("IMAGUNITY_DIRECTORY_ORGANIZATION_KEY");
        assertThat(configuration.publisher(new DirectoryServerProperties(null, "r", ""), encoder)
                .loadUserByUsername("r").getPassword()).startsWith("{bcrypt}");
        String hash = encoder.encode("x");
        assertThat(configuration.publisher(new DirectoryServerProperties(null, "r", hash), encoder)
                .loadUserByUsername("r").getPassword()).isEqualTo(hash);
        assertThat(configuration.publisher(new DirectoryServerProperties(null, "r", null), encoder)
                .loadUserByUsername("r")).isNotNull();
    }
}
