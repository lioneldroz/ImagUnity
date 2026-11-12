package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryAdminTest {

    private static final String BOX_ID = "3f6c0d2e-8a8b-4b6e-9a51-0f6d3c2b1a00";

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Instant now = Instant.parse("2026-11-12T10:00:00Z");

    @BeforeEach
    void reset() {
        out.reset();
        err.reset();
    }

    private static final java.util.Map<String, String> PHRASES = java.util.Map.of(
            DirectoryAdmin.ORGANIZATION_PASSPHRASE, "phrase du responsable", DirectoryAdmin.BOX_PASSPHRASE,
            "phrase de la box de test");
    private java.util.Map<String, String> environment = PHRASES;
    private java.util.Deque<String> typed = new java.util.ArrayDeque<>();

    private int run(String... args) {
        return new DirectoryAdmin(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Clock.fixed(now, ZoneOffset.UTC), environment,
                label -> typed.isEmpty() ? null : typed.poll().toCharArray())
                .run(args);
    }

    private static java.util.Map<String, String> with(String key, String value) {
        java.util.Map<String, String> map = new java.util.HashMap<>(PHRASES);
        map.put(key, value);
        return map;
    }

    private String p(String name) {
        return dir.resolve(name).toString();
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void fullLifecycleFromOrganizationToRevocation() {
        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json"))).isZero();
        assertThat(output()).contains("org.json.pub.json").containsPattern("[A-Z2-7]{4}(-[A-Z2-7]{4}){5}");
        assertThat(run("empreinte", "--cle-publique", p("org.json.pub.json"))).isZero();
        assertThat(output()).contains("Organisation « chu-exemple »");

        assertThat(run("box-demande", "--cles", p("box.json"), "--box-id", BOX_ID, "--nom", "CHU Exemple",
                "--simplex", "simplex:/contact#x", "--sortie", p("demande.json"))).isZero();
        assertThat(output()).contains("Identité de la box créée");
        out.reset();
        assertThat(run("box-demande", "--cles", p("box.json"), "--box-id", BOX_ID, "--nom", "CHU Exemple",
                "--sortie", p("demande.json"))).as("identité réutilisée").isZero();
        assertThat(output()).doesNotContain("Identité de la box créée");

        assertThat(run("enroler", "--cles", p("org.json"), "--demande", p("demande.json"), "--validite-jours", "30",
                "--sortie", p("entree.json"))).isZero();
        assertThat(output()).contains("enrôlée pour 30 jours, n° 1").contains("Vérifiez hors bande");
        assertThat(run("verifier", "--cle-publique", p("org.json.pub.json"), "--entree", p("entree.json"))).isZero();
        assertThat(output()).contains("Signature valide").contains("ACTIVE");

        assertThat(run("revoquer", "--cles", p("org.json"), "--entree", p("entree.json"), "--motif", "Box volée",
                "--sortie", p("revocation.json"))).isZero();
        assertThat(run("verifier", "--cle-publique", p("org.json.pub.json"), "--entree", p("revocation.json")))
                .as("box révoquée : code 1").isEqualTo(1);
        assertThat(output()).contains("REVOKED");
    }

    @Test
    void usageErrors() {
        assertThat(run()).isEqualTo(2);
        assertThat(run("inconnue")).isEqualTo(2);
        assertThat(run("empreinte", "cle-publique", "x")).isEqualTo(2);
        assertThat(run("empreinte", "--cle-publique")).isEqualTo(2);
        assertThat(run("empreinte")).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Option manquante : --cle-publique").contains("Usage");
    }

    @Test
    void refusalsAreReportedWithoutStackTrace() throws Exception {
        run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json"));
        run("organisation-creer", "--organisation", "chu-voisin", "--cles", p("voisin.json"));
        // Clé publique d'une autre organisation à côté de la clé privée : refus.
        Files.copy(dir.resolve("voisin.json.pub.json"), dir.resolve("org.json.pub.json"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        run("box-demande", "--cles", p("box.json"), "--box-id", BOX_ID, "--nom", "B", "--sortie", p("d.json"));
        err.reset();

        assertThat(run("enroler", "--cles", p("org.json"), "--demande", p("d.json"), "--sortie", p("e.json")))
                .isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("ne correspond pas");
        assertThat(run("organisation-creer", "--organisation", "Majuscules", "--cles", p("x.json"))).isEqualTo(1);
        assertThat(run("empreinte", "--cle-publique", p("absent.json"))).isEqualTo(1);
        assertThat(run("box-demande", "--cles", p("box.json"), "--box-id", "pas-un-uuid", "--nom", "B",
                "--sortie", p("d.json"))).isEqualTo(1);
    }

    @Test
    void publishesToTheDirectory() throws Exception {
        java.util.List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        int[] status = {201};
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/entries", exchange -> {
            received.add(exchange.getRequestHeaders().getFirst("Authorization") + " "
                    + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = status[0] == 409 ? "{\"detail\":\"Retour en arrière\"}".getBytes() : new byte[0];
            exchange.sendResponseHeaders(status[0], body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        try {
            run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json"));
            run("box-demande", "--cles", p("box.json"), "--box-id", BOX_ID, "--nom", "B", "--sortie", p("d.json"));
            run("enroler", "--cles", p("org.json"), "--demande", p("d.json"), "--sortie", p("e.json"));
            out.reset();

            assertThat(run("publier", "--annuaire", url, "--entree", p("e.json"), "--compte", "responsable:secret"))
                    .isZero();
            assertThat(output()).contains("Entrée publiée");
            status[0] = 200;
            environment = with(DirectoryAdmin.ACCOUNT_VARIABLE, "responsable:secret");
            assertThat(run("publier", "--annuaire", url, "--entree", p("e.json"))).isZero();
            assertThat(output()).contains("déjà publiée");
            status[0] = 409;
            assertThat(run("publier", "--annuaire", url, "--entree", p("e.json"))).isEqualTo(1);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("HTTP 409").contains("Retour en arrière");

            String basic = "Basic " + java.util.Base64.getEncoder().encodeToString("responsable:secret".getBytes());
            assertThat(received).hasSize(3).allSatisfy(r -> assertThat(r).startsWith(basic).contains("\"payload\""));
            environment = PHRASES;
            assertThat(run("publier", "--annuaire", url, "--entree", p("e.json"))).as("compte manquant").isEqualTo(2);
            assertThat(run("publier", "--annuaire", url, "--entree", p("e.json"), "--compte", "sans-deux-points"))
                    .isEqualTo(2);
        } finally {
            server.stop(0);
        }
        assertThat(run("publier", "--annuaire", url, "--entree", p("e.json"), "--compte", "a:b"))
                .as("annuaire injoignable").isEqualTo(1);
    }

    @Test
    void keysAreProtectedByPassphrase() throws Exception {
        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json"))).isZero();
        assertThat(output()).contains("protégée par phrase de passe");
        assertThat(KeyFiles.isProtected(dir.resolve("org.json"))).isTrue();
        run("box-demande", "--cles", p("box.json"), "--box-id", BOX_ID, "--nom", "B", "--sortie", p("d.json"));
        assertThat(KeyFiles.isProtected(dir.resolve("box.json"))).isTrue();

        environment = java.util.Map.of();
        assertThat(run("enroler", "--cles", p("org.json"), "--demande", p("d.json"), "--sortie", p("e.json")))
                .as("phrase manquante").isEqualTo(2);
        typed.add("mauvaise phrase de passe");
        assertThat(run("enroler", "--cles", p("org.json"), "--demande", p("d.json"), "--sortie", p("e.json")))
                .as("phrase incorrecte").isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("Phrase de passe incorrecte");
        typed.add("phrase du responsable");
        assertThat(run("enroler", "--cles", p("org.json"), "--demande", p("d.json"), "--sortie", p("e.json")))
                .as("phrase saisie au terminal").isZero();
    }

    @Test
    void creationNeedsAPassphraseOrAnExplicitChoice() throws Exception {
        environment = java.util.Map.of();
        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json")))
                .isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--sans-phrase");

        typed.addAll(java.util.List.of("phrase saisie deux fois", "phrase saisie autrement"));
        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json")))
                .as("confirmation différente").isEqualTo(1);
        typed.clear();
        typed.addAll(java.util.List.of("courte", "courte"));
        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org2.json")))
                .as("trop courte").isEqualTo(1);
        typed.clear();
        typed.addAll(java.util.List.of("phrase saisie deux fois", "phrase saisie deux fois"));
        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org3.json"))).isZero();
        assertThat(KeyFiles.isProtected(dir.resolve("org3.json"))).isTrue();

        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("clair.json"),
                "--sans-phrase")).isZero();
        assertThat(KeyFiles.isProtected(dir.resolve("clair.json"))).isFalse();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("sans phrase de passe (déconseillé)");
    }

    @Test
    void existingClearKeysCanBeProtected() throws Exception {
        run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json"), "--sans-phrase");
        run("box-demande", "--cles", p("box.json"), "--box-id", BOX_ID, "--nom", "B", "--sortie", p("d.json"),
                "--sans-phrase");
        err.reset();
        assertThat(run("enroler", "--cles", p("org.json"), "--demande", p("d.json"), "--sortie", p("e.json"))).isZero();
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("clé non protégée");

        assertThat(run("proteger", "--cles", p("org.json"))).isZero();
        assertThat(run("proteger", "--cles", p("box.json"), "--box")).isZero();
        assertThat(KeyFiles.isProtected(dir.resolve("org.json"))).isTrue();
        assertThat(KeyFiles.isProtected(dir.resolve("box.json"))).isTrue();
        assertThat(run("proteger", "--cles", p("org.json"))).as("déjà protégée").isEqualTo(1);
        environment = java.util.Map.of();
        assertThat(run("proteger", "--cles", p("org.json"))).as("phrase manquante").isEqualTo(2);
        assertThat(run("box-demande", "--cles", p("box.json"), "--box-id", BOX_ID, "--nom", "B", "--sortie",
                p("d.json"))).as("clé de box protégée sans phrase").isEqualTo(2);
        assertThat(run("proteger", "--cles", p("org.json"), "inattendu")).isEqualTo(2);
    }
}
