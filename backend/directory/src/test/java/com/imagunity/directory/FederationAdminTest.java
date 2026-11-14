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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Outil du responsable : reconnaissance d'organisations partenaires (annuaire simulé, données synthétiques). */
class FederationAdminTest {

    private static final String PHRASE = "phrase du responsable";

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Instant now = Instant.parse("2026-12-01T10:00:00Z");
    private final Deque<String> typed = new ArrayDeque<>();
    private HttpServer server;
    private String url;
    private final Map<String, SignedDocument> published = new LinkedHashMap<>();
    private int publishStatus = 201;
    private int listStatus = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/recognitions", exchange -> {
            String body = "";
            int status;
            if (exchange.getRequestMethod().equals("POST")) {
                SignedDocument document = SignedDocument.fromJson(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                status = publishStatus;
                if (status == 201) {
                    published.put(Json.read(document.payload(), Recognition.class, "r").partner().fingerprint(),
                            document);
                }
            } else {
                status = listStatus;
                body = "{\"organization\":\"grand-est\",\"recognitions\":[" + String.join(",",
                        published.values().stream().map(SignedDocument::toJson).toList()) + "]}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
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
                Map.of(DirectoryAdmin.ORGANIZATION_PASSPHRASE, PHRASE, DirectoryAdmin.ACCOUNT_VARIABLE,
                        "responsable:depot"), label -> typed.isEmpty() ? null : typed.poll().toCharArray()).run(args);
    }

    private String p(String name) {
        return dir.resolve(name).toString();
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private Recognition current(String fingerprint) {
        return Json.read(published.get(fingerprint).payload(), Recognition.class, "r");
    }

    @Test
    void theResponsibleRecognizesListsRenewsAndRevokesAPartner() throws IOException {
        assertThat(run("organisation-creer", "--organisation", "grand-est", "--cles", p("a.json"))).isZero();
        assertThat(run("organisation-creer", "--organisation", "bourgogne-fc", "--cles", p("b.json"))).isZero();
        HybridPublicKey keyB = KeyFiles.readOrganizationPublic(Path.of(p("b.json.pub.json"))).key();
        String fpB = keyB.fingerprint();
        String[] recognize = {"reconnaitre", "--cles", p("a.json"), "--annuaire", url, "--partenaire",
            p("b.json.pub.json"), "--annuaire-partenaire", "https://annuaire.bourgogne-fc.example", "--relais",
            "https://relais.bourgogne-fc.example, https://relais2.bourgogne-fc.example", "--groupes-locaux",
            "imagerie-nord", "--groupes-partenaire", "*"};

        assertThat(run("reconnaissances", "--cles", p("a.json"), "--annuaire", url)).isZero();
        assertThat(output()).contains("Aucune organisation partenaire reconnue");

        // Empreinte non confirmée : rien n'est signé.
        typed.add("non");
        assertThat(run(recognize)).isEqualTo(1);
        assertThat(output()).contains(keyB.shortCode()).contains("nouvelle reconnaissance");
        assertThat(published).isEmpty();

        typed.add("oui");
        assertThat(run(recognize)).isZero();
        assertThat(output()).contains("à son tour");
        Recognition r = current(fpB);
        assertThat(r.serial()).isEqualTo(1);
        assertThat(r.scope().localGroups()).containsExactly("imagerie-nord");
        assertThat(r.scope().partnerGroups()).containsExactly("*");
        assertThat(r.partner().relays()).hasSize(2);
        assertThat(r.validUntil()).isEqualTo(now.plus(Duration.ofDays(90)));

        // Réémission (périmètre vide, 30 jours) : numéro suivant.
        now = now.plus(Duration.ofDays(10));
        assertThat(run("reconnaitre", "--cles", p("a.json"), "--annuaire", url, "--partenaire", p("b.json.pub.json"),
                "--annuaire-partenaire", "https://annuaire.bourgogne-fc.example", "--relais",
                "https://relais.bourgogne-fc.example", "--validite-jours", "30", "--confirmer")).isZero();
        assertThat(output()).contains("réémission (n° 2)").contains("aucun échange possible");
        assertThat(current(fpB).serial()).isEqualTo(2);
        assertThat(current(fpB).scope().empty()).isTrue();

        assertThat(run("reconnaissances", "--cles", p("a.json"), "--annuaire", url)).isZero();
        assertThat(output()).contains("« bourgogne-fc »").contains("active jusqu'au 2027-01-10").contains("n° 2")
                .contains("groupes locaux : aucun");

        assertThat(run("reconnaissance-revoquer", "--cles", p("a.json"), "--annuaire", url, "--empreinte",
                keyB.shortCode().toLowerCase(), "--motif", "fin du partenariat", "--confirmer")).isZero();
        assertThat(current(fpB).revoked()).isTrue();
        assertThat(run("reconnaissances", "--cles", p("a.json"), "--annuaire", url)).isZero();
        assertThat(output()).contains("révoquée le 2026-12-11 (fin du partenariat)");
        now = now.plus(Duration.ofDays(100));
        assertThat(run("reconnaissances", "--cles", p("a.json"), "--annuaire", url)).isZero();
        assertThat(output()).as("révoquée l'emporte sur expirée").contains("révoquée");
    }

    @Test
    void errorsAreReported() throws IOException {
        assertThat(run("organisation-creer", "--organisation", "grand-est", "--cles", p("a.json"))).isZero();
        assertThat(run("organisation-creer", "--organisation", "bourgogne-fc", "--cles", p("b.json"))).isZero();
        String[] recognize = {"reconnaitre", "--cles", p("a.json"), "--annuaire", url, "--partenaire",
            p("b.json.pub.json"), "--annuaire-partenaire", "https://annuaire.b.example", "--relais",
            "https://relais.b.example", "--confirmer"};

        assertThat(run(recognize[0], recognize[1], recognize[2], recognize[3], recognize[4], recognize[5],
                recognize[6], recognize[7], recognize[8], recognize[9], recognize[10], "--validite-jours", "400"))
                .isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("de 1 à 365");
        publishStatus = 409;
        assertThat(run(recognize)).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("HTTP 409");
        publishStatus = 201;
        assertThat(run(recognize)).isZero();

        // Reconnaissance publiée par quelqu'un d'autre sur l'annuaire : écartée à la lecture.
        Organization intruder = new Organization("grand-est", HybridKeyPair.generate());
        published.put("x", intruder.recognize(new Recognition.Partner("autre", HybridKeyPair.generate().publicKey()
                .encoded(), "https://a.example", java.util.List.of("https://r.example")), Recognition.Scope.none(),
                now, Duration.ofDays(1)));
        assertThat(run("reconnaissances", "--cles", p("a.json"), "--annuaire", url)).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Reconnaissance écartée");

        assertThat(run("reconnaissance-revoquer", "--cles", p("a.json"), "--annuaire", url, "--empreinte",
                "INCO-NNUE", "--motif", "x", "--confirmer")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Aucune reconnaissance");
        String fpB = KeyFiles.readOrganizationPublic(Path.of(p("b.json.pub.json"))).key().fingerprint();
        assertThat(run("reconnaissance-revoquer", "--cles", p("a.json"), "--annuaire", url, "--empreinte", fpB,
                "--motif", "x")).as("non confirmé").isEqualTo(1);
        publishStatus = 500;
        assertThat(run("reconnaissance-revoquer", "--cles", p("a.json"), "--annuaire", url, "--empreinte", fpB,
                "--motif", "x", "--confirmer")).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("HTTP 500");

        listStatus = 503;
        assertThat(run("reconnaissances", "--cles", p("a.json"), "--annuaire", url)).isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("HTTP 503");
        assertThat(run("reconnaissances", "--annuaire", url)).isEqualTo(2);
    }
}
