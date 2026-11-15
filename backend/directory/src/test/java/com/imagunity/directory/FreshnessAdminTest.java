package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Outil du responsable : délégation de la clé de fraîcheur et vérification de la preuve publiée. */
class FreshnessAdminTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Instant now = Instant.parse("2026-12-01T10:00:00Z");
    private HttpServer server;
    private String url;
    private volatile String freshness;
    private final List<SignedDocument> entries = new CopyOnWriteArrayList<>();
    private final List<SignedDocument> recognitions = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/freshness") ? freshness
                    : path.endsWith("/recognitions") ? "{\"organization\":\"grand-est\",\"recognitions\":["
                    + String.join(",", recognitions.stream().map(SignedDocument::toJson).toList()) + "]}"
                    : "{\"organization\":\"grand-est\",\"entries\":[" + String.join(",",
                    entries.stream().map(SignedDocument::toJson).toList()) + "]}";
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
            } else {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        return new DirectoryAdmin(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Clock.fixed(now, ZoneOffset.UTC),
                Map.of(DirectoryAdmin.ORGANIZATION_PASSPHRASE, "phrase du responsable"), label -> null).run(args);
    }

    private String p(String name) {
        return dir.resolve(name).toString();
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void theResponsibleDelegatesTheFreshnessKeyAndChecksThePublishedProof() throws IOException {
        assertThat(run("organisation-creer", "--organisation", "grand-est", "--cles", p("a.json"))).isZero();
        String[] delegate = {"fraicheur-deleguer", "--cles", p("a.json"), "--sortie-cle", p("fraicheur.json"),
            "--sortie-delegation", p("delegation.json")};
        assertThat(run(delegate)).isZero();
        assertThat(output()).contains("n° 1").contains("IMAGUNITY_DIRECTORY_FRESHNESS_KEY");
        assertThat(run(delegate)).as("renouvellement").isZero();
        assertThat(output()).contains("n° 2");
        assertThat(run(delegate[0], delegate[1], delegate[2], delegate[3], delegate[4], delegate[5], delegate[6],
                "--validite-jours", "120")).isEqualTo(1);

        HybridKeyPair key = KeyFiles.readPrivate(Path.of(p("fraicheur.json")));
        SignedDocument delegation = SignedDocument.fromJson(java.nio.file.Files.readString(Path.of(p("delegation.json"))));
        KeyFiles.OrganizationPublicKey own = KeyFiles.readOrganizationPublic(Path.of(p("a.json.pub.json")));
        Organization org = new Organization("grand-est", KeyFiles.readPrivate(Path.of(p("a.json")),
                "phrase du responsable".toCharArray()));
        UUID box = UUID.randomUUID();
        SignedDocument entry = org.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), box, "Box A", Map.of(), now),
                now, Duration.ofDays(90), 1);
        entries.add(entry);
        Recognition.Partner partner = new Recognition.Partner("bourgogne-fc", HybridKeyPair.generate().publicKey()
                .encoded(), "https://annuaire.b.example", List.of("https://relais.b.example"));
        recognitions.add(org.recognize(partner, Recognition.Scope.none(), now, Duration.ofDays(90)));

        assertThat(run("fraicheur-verifier", "--cles", p("a.json"), "--annuaire", url)).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("ne publie pas");

        now = now.plusSeconds(120);
        freshness = new Freshness(delegation, FreshnessProof.sign(key, own.organization(), now.minusSeconds(120),
                Map.of(box, 1L), Map.of(partner.fingerprint(), 1L))).toJson();
        assertThat(run("fraicheur-verifier", "--cles", p("a.json"), "--annuaire", url)).as(() -> output() + err).isZero();
        assertThat(output()).contains("signée il y a 2 min").contains("n° 2").contains("1 box")
                .contains("correspond");

        // L'annuaire sert une preuve qui ne dit pas la même chose que ce qu'il publie.
        freshness = new Freshness(delegation, FreshnessProof.sign(key, own.organization(), now, Map.of(box, 2L),
                Map.of())).toJson();
        assertThat(run("fraicheur-verifier", "--cles", p("a.json"), "--annuaire", url)).isEqualTo(1);
        assertThat(output()).contains("Écart : box « Box A » publiée au n° 1, preuve au n° 2")
                .contains("Écart : reconnaissance de « bourgogne-fc »");

        // Délégation proche de sa fin.
        now = now.plus(Duration.ofDays(25));
        freshness = new Freshness(delegation, FreshnessProof.sign(key, own.organization(), now, Map.of(box, 1L),
                Map.of(partner.fingerprint(), 1L))).toJson();
        entries.add(SignedDocument.sign("{}", HybridKeyPair.generate()));
        assertThat(run("fraicheur-verifier", "--cles", p("a.json"), "--annuaire", url)).isEqualTo(1);
        assertThat(output()).contains("proche de sa fin");
    }
}
