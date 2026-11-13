package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Accès à la console par HTTP réel : compte opérateur, en-tête de la console, santé libre. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SecurityWiringTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:securite-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        registry.add("imagunity.dicomweb.password", () -> "pacs-de-test");
    }

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + environment.getProperty("local.server.port")
                + path));
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void apiRequiresTheOperatorAccount() throws Exception {
        HttpResponse<String> anonymous = send(request("/api/v1/box"));
        HttpResponse<String> wrong = send(request("/api/v1/box").header("Authorization", basic("operateur", "faux")));
        HttpResponse<String> operator = send(request("/api/v1/box")
                .header("Authorization", basic("operateur", "mot-de-passe-de-test")));

        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue("WWW-Authenticate")).hasValueSatisfying(
                h -> assertThat(h).contains("Basic").contains("ImagUnity"));
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(operator.statusCode()).isEqualTo(200);
        assertThat(operator.headers().firstValue("Set-Cookie")).as("sans session").isEmpty();
    }

    @Test
    void theConsoleIsServedToTheOperatorOnlyWithAStrictContentSecurityPolicy() throws Exception {
        HttpResponse<String> anonymous = send(request("/"));
        assertThat(anonymous.statusCode()).as("console réservée au compte opérateur").isEqualTo(401);
        HttpResponse<String> api = send(request("/api/v1/box")
                .header("Authorization", basic("operateur", "mot-de-passe-de-test")));
        assertThat(api.headers().firstValue("Content-Security-Policy")).hasValue(
                SecurityConfiguration.CONTENT_SECURITY_POLICY);
        // La console n'est dans le jar que s'il a été construit avec elle (sans -Dconsole.skip=true).
        org.junit.jupiter.api.Assumptions.assumeTrue(getClass().getResource("/static/index.html") != null,
                "box construite sans console");
        HttpResponse<String> console = send(request("/")
                .header("Authorization", basic("operateur", "mot-de-passe-de-test")));
        assertThat(console.statusCode()).isEqualTo(200);
        assertThat(console.headers().firstValue("Content-Type")).hasValueSatisfying(t -> assertThat(t).startsWith("text/html"));
        assertThat(console.body()).contains("ImagUnity").contains("<script type=\"module\"");
        assertThat(console.headers().firstValue("Content-Security-Policy")).hasValue(
                SecurityConfiguration.CONTENT_SECURITY_POLICY);
        assertThat(console.headers().firstValue("X-Frame-Options")).hasValue("DENY");
    }

    @Test
    void stateChangesRequireTheConsoleHeader() throws Exception {
        String operator = basic("operateur", "mot-de-passe-de-test");
        HttpResponse<String> withoutHeader = send(request("/api/v1/transfers/" + UUID.randomUUID() + "/retry")
                .header("Authorization", operator).POST(HttpRequest.BodyPublishers.noBody()));
        HttpResponse<String> withHeader = send(request("/api/v1/transfers/" + UUID.randomUUID() + "/retry")
                .header("Authorization", operator).header(SecurityConfiguration.CONSOLE_HEADER, "1")
                .POST(HttpRequest.BodyPublishers.noBody()));

        assertThat(withoutHeader.statusCode()).isEqualTo(403);
        assertThat(withoutHeader.body()).contains(SecurityConfiguration.CONSOLE_HEADER);
        assertThat(withHeader.statusCode()).as("transfert inconnu, mais requête admise").isEqualTo(404);
    }

    @Test
    void eachAccountOpensOnlyItsOwnPart() throws Exception {
        String operator = basic("operateur", "mot-de-passe-de-test");
        String pacs = basic("pacs", "pacs-de-test");
        HttpRequest.BodyPublisher empty = HttpRequest.BodyPublishers.noBody();

        assertThat(send(request("/api/v1/box").header("Authorization", pacs)).statusCode())
                .as("le compte DICOMweb n'ouvre pas la console").isEqualTo(403);
        assertThat(send(request("/dicomweb/IU_TO_B/studies").header("Authorization", operator)
                .header(SecurityConfiguration.CONSOLE_HEADER, "1").POST(empty)).statusCode())
                .as("le compte opérateur ne dépose pas d'instances").isEqualTo(403);
        assertThat(send(request("/dicomweb/IU_TO_B/studies").POST(empty)).statusCode()).isEqualTo(401);
        HttpResponse<String> deposit = send(request("/dicomweb/IU_TO_B/studies").header("Authorization", pacs)
                .header("Content-Type", "multipart/related; type=\"application/dicom\"; boundary=x").POST(empty));
        assertThat(deposit.statusCode()).as("sans en-tête de la console ; entrée DICOM désactivée ici").isEqualTo(404);
        assertThat(deposit.body()).contains("Entrée DICOM désactivée");
        assertThat(send(request("/dicomweb/IU_TO_B/studies/2.25.1").header("Authorization", pacs).POST(empty))
                .statusCode()).isEqualTo(404);
    }

    @Test
    void dicomwebAccountIsOptionalAndDistinct() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        SecurityConfiguration configuration = new SecurityConfiguration();
        ConsoleAccessProperties console = new ConsoleAccessProperties("operateur", "secret-operateur");

        assertThat(((org.springframework.security.provisioning.InMemoryUserDetailsManager) configuration.accounts(
                console, new DicomWebProperties("pacs", ""), encoder)).userExists("pacs")).isFalse();
        assertThat(((org.springframework.security.provisioning.InMemoryUserDetailsManager) configuration.accounts(
                console, new DicomWebProperties("pacs", null), encoder)).userExists("pacs")).isFalse();
        String hash = encoder.encode("pacs");
        assertThat(configuration.accounts(console, new DicomWebProperties("pacs", hash), encoder)
                .loadUserByUsername("pacs").getPassword()).isEqualTo(hash);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> configuration.accounts(console,
                new DicomWebProperties("operateur", "autre"), encoder)).hasMessageContaining("distinct");
    }

    @Test
    void healthIsOpen() throws Exception {
        assertThat(send(request("/actuator/health")).statusCode()).isEqualTo(200);
    }

    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path dir;

    private static String readString(java.nio.file.Path file) {
        try {
            return java.nio.file.Files.readString(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    void passwordIsGeneratedWhenMissingAndHashesAreKept() throws java.io.IOException {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        SecurityConfiguration configuration = new SecurityConfiguration();

        java.nio.file.Path file = dir.resolve("donnees/mot-de-passe-console.txt");
        UserDetails generated = configuration.operator(new ConsoleAccessProperties("operateur", " ", file), encoder)
                .loadUserByUsername("operateur");
        String written = readString(file).strip();
        assertThat(encoder.matches(written, generated.getPassword())).as("mot de passe écrit dans le fichier").isTrue();
        assertThat(java.nio.file.Files.getPosixFilePermissions(file)).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        java.nio.file.Files.createDirectories(dir.resolve("bloque"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SecurityConfiguration.writeOwnerOnly(
                dir.resolve("bloque"), "x")).isInstanceOf(java.io.UncheckedIOException.class);
        String hash = encoder.encode("secret");
        UserDetails hashed = configuration.operator(new ConsoleAccessProperties("chef", hash), encoder)
                .loadUserByUsername("chef");

        assertThat(generated.getPassword()).startsWith("{bcrypt}");
        assertThat(hashed.getPassword()).isEqualTo(hash);
        assertThat(encoder.matches("secret", hashed.getPassword())).isTrue();
        assertThat(SecurityConfiguration.generatedPassword()).hasSize(24)
                .isNotEqualTo(SecurityConfiguration.generatedPassword());
        assertThat(configuration.operator(new ConsoleAccessProperties("x", null, dir.resolve("x.txt")), encoder)
                .loadUserByUsername("x")).isNotNull();
        assertThat(new ConsoleAccessProperties("y", "z").generatedPasswordFile()).isNotNull();
    }
}
