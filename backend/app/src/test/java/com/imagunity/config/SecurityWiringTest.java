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
    void healthIsOpen() throws Exception {
        assertThat(send(request("/actuator/health")).statusCode()).isEqualTo(200);
    }

    @Test
    void passwordIsGeneratedWhenMissingAndHashesAreKept() {
        PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
        SecurityConfiguration configuration = new SecurityConfiguration();

        UserDetails generated = configuration.operator(new ConsoleAccessProperties("operateur", " "), encoder)
                .loadUserByUsername("operateur");
        String hash = encoder.encode("secret");
        UserDetails hashed = configuration.operator(new ConsoleAccessProperties("chef", hash), encoder)
                .loadUserByUsername("chef");

        assertThat(generated.getPassword()).startsWith("{bcrypt}");
        assertThat(hashed.getPassword()).isEqualTo(hash);
        assertThat(encoder.matches("secret", hashed.getPassword())).isTrue();
        assertThat(SecurityConfiguration.generatedPassword()).hasSize(24)
                .isNotEqualTo(SecurityConfiguration.generatedPassword());
        assertThat(configuration.operator(new ConsoleAccessProperties("x", null), encoder).loadUserByUsername("x"))
                .isNotNull();
    }
}
