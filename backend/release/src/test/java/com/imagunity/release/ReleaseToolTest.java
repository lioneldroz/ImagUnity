package com.imagunity.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.KeyFiles;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseToolTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private final Map<String, String> environment = new HashMap<>();
    private final Deque<String> typed = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        environment.put(ReleaseTool.PASSPHRASE, "phrase de l'éditeur de test");
    }

    private int run(String... args) {
        out.reset();
        err.reset();
        return new ReleaseTool(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"), ZoneOffset.UTC), environment,
                label -> typed.isEmpty() ? null : typed.poll().toCharArray(), HttpClient.newHttpClient()).run(args);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String p(String name) {
        return dir.resolve(name).toString();
    }

    private Path jar(String name, String content) throws IOException {
        return Files.writeString(Files.createDirectories(dir.resolve("artefacts")).resolve(name), content);
    }

    /** Clé de l'éditeur créée, empreinte épinglée dans l'environnement de la box. */
    private void publisher() {
        assertThat(run("cle-creer", "--editeur", "ImagUnity", "--cles", p("editeur.json"))).isZero();
        String code = output().replaceAll("(?s).*hors bande : ", "").strip();
        environment.put("IMAGUNITY_RELEASE_KEY", p("editeur.json.pub.json"));
        environment.put("IMAGUNITY_RELEASE_CODE", code.toLowerCase());
        environment.put("IMAGUNITY_RELEASE_DIR", p("data/mises-a-jour"));
    }

    private void sign(Path artifact, String version, long sequence, String output) {
        assertThat(run("signer", "--cles", p("editeur.json"), "--artefact", artifact.toString(), "--version", version,
                "--sequence", Long.toString(sequence), "--sortie", output)).as(errors()).isZero();
    }

    /** Version livrée avec l'image : jar et manifeste côte à côte. */
    private Path bundled(String version, long sequence) throws IOException {
        Path jar = Files.writeString(Files.createDirectories(dir.resolve("app")).resolve("imagunity-box.jar"),
                "logiciel " + version);
        sign(jar, version, sequence, jar + ReleaseStore.MANIFEST_SUFFIX);
        environment.put("IMAGUNITY_RELEASE_BUNDLED", jar.toString());
        return jar;
    }

    /** Version déposée à la main dans le dépôt de la box. */
    private Path deposit(String content, String version, long sequence) throws IOException {
        Path depot = Files.createDirectories(dir.resolve("data/mises-a-jour/depot"));
        Path jar = Files.writeString(depot.resolve("imagunity-box-" + version + ".jar"), content);
        sign(jar, version, sequence, jar + ReleaseStore.MANIFEST_SUFFIX);
        return jar;
    }

    @Test
    void publisherSignsAndAnyoneVerifies() throws IOException {
        publisher();
        assertThat(output()).contains("Clé publique, à installer dans les box").containsPattern("[A-Z2-7]{4}(-[A-Z2-7]{4}){5}");
        assertThat(KeyFiles.isProtected(dir.resolve("editeur.json"))).isTrue();
        assertThat(run("empreinte", "--cle-publique", p("editeur.json.pub.json"))).isZero();
        assertThat(output()).contains("Éditeur « ImagUnity »");

        Path jar = jar("imagunity-box.jar", "version 0.2.0");
        assertThat(run("signer", "--cles", p("editeur.json"), "--artefact", jar.toString(), "--version", "0.2.0",
                "--sequence", "2")).isZero();
        assertThat(output()).contains("0.2.0 (n° 2) signée");
        Path manifest = Path.of(jar + ReleaseStore.MANIFEST_SUFFIX);
        assertThat(run("verifier", "--cle-publique", p("editeur.json.pub.json"), "--manifeste", manifest.toString(),
                "--artefact", jar.toString())).isZero();
        assertThat(output()).contains("Signature valide : version 0.2.0 (n° 2)");

        Files.writeString(jar, "version 0.2.0, modifiée");
        assertThat(run("verifier", "--cle-publique", p("editeur.json.pub.json"), "--manifeste", manifest.toString(),
                "--artefact", jar.toString())).isEqualTo(1);
        assertThat(errors()).contains("Artefact différent du manifeste signé");
        Files.writeString(jar, "version 0.2.1");
        assertThat(run("verifier", "--cle-publique", p("editeur.json.pub.json"), "--manifeste", manifest.toString(),
                "--artefact", jar.toString())).isEqualTo(1);
        assertThat(errors()).contains("(empreinte)");

        assertThat(run("cle-creer", "--editeur", "Imposteur", "--cles", p("autre.json"))).isZero();
        Files.writeString(jar, "version 0.2.0");
        assertThat(run("verifier", "--cle-publique", p("autre.json.pub.json"), "--manifeste", manifest.toString(),
                "--artefact", jar.toString())).isEqualTo(1);
        assertThat(errors()).contains("non signé par la clé de l'éditeur");
        assertThat(run("verifier", "--cle-publique", p("editeur.json.pub.json"), "--manifeste", manifest.toString(),
                "--artefact", jar.toString(), "--produit", "autre-produit")).isEqualTo(1);
        assertThat(errors()).contains("autre produit");
    }

    @Test
    void publisherKeyNeedsAPassphraseOrAnExplicitChoice() throws IOException {
        environment.clear();
        assertThat(run("cle-creer", "--editeur", "E", "--cles", p("a.json"))).isEqualTo(2);
        typed.addAll(java.util.List.of("une phrase saisie", "une autre phrase"));
        assertThat(run("cle-creer", "--editeur", "E", "--cles", p("a.json"))).isEqualTo(1);
        assertThat(errors()).contains("diffèrent");
        typed.clear();
        typed.addAll(java.util.List.of("courte", "courte"));
        assertThat(run("cle-creer", "--editeur", "E", "--cles", p("b.json"))).isEqualTo(1);
        typed.clear();
        typed.addAll(java.util.List.of("une phrase saisie", "une phrase saisie"));
        assertThat(run("cle-creer", "--editeur", "E", "--cles", p("c.json"))).isZero();

        Path jar = jar("x.jar", "x");
        assertThat(run("signer", "--cles", p("c.json"), "--artefact", jar.toString(), "--version", "1",
                "--sequence", "1")).as("phrase manquante").isEqualTo(2);
        typed.add("une phrase saisie");
        assertThat(run("signer", "--cles", p("c.json"), "--artefact", jar.toString(), "--version", "1",
                "--sequence", "1")).isZero();

        assertThat(run("cle-creer", "--editeur", "E", "--cles", p("clair.json"), "--sans-phrase")).isZero();
        assertThat(errors()).contains("sans phrase de passe");
        assertThat(run("signer", "--cles", p("clair.json"), "--artefact", jar.toString(), "--version", "1",
                "--sequence", "1")).isZero();
        assertThat(errors()).contains("non protégée");
        assertThat(run("signer", "--cles", p("clair.json"), "--artefact", jar.toString(), "--version", "1 bis",
                "--sequence", "1")).isEqualTo(1);
        assertThat(errors()).contains("Version invalide");
    }

    @Test
    void usageErrors() {
        assertThat(run()).isEqualTo(2);
        assertThat(run("inconnue")).isEqualTo(2);
        assertThat(run("empreinte")).isEqualTo(2);
        assertThat(run("empreinte", "--cle-publique")).isEqualTo(2);
        assertThat(run("empreinte", "inattendu")).isEqualTo(2);
        assertThat(errors()).contains("Usage");
        assertThat(run("empreinte", "--cle-publique", p("absente.json"))).isEqualTo(1);
    }

    @Test
    void withoutPublisherKeyTheBundledVersionStartsWithAWarning() {
        environment.put("IMAGUNITY_RELEASE_BUNDLED", "/app/imagunity-box.jar");
        assertThat(run("choisir")).isZero();
        assertThat(output()).isEqualTo("/app/imagunity-box.jar\tnon-verifiee\t0\n");
        assertThat(errors()).contains("DÉSACTIVÉE");
    }

    @Test
    void unconfirmedPublisherKeyPreventsStarting() throws IOException {
        publisher();
        bundled("0.1.0", 1);
        environment.put("IMAGUNITY_RELEASE_CODE", "AAAA-BBBB");
        assertThat(run("choisir")).isEqualTo(1);
        assertThat(errors()).contains("empreinte de l'éditeur non confirmée");
        environment.remove("IMAGUNITY_RELEASE_CODE");
        assertThat(run("choisir")).isEqualTo(1);
    }

    @Test
    void depositedVersionsAreVerifiedInstalledAndNeverRolledBack() throws IOException {
        publisher();
        Path image = bundled("0.1.0", 1);

        assertThat(run("choisir")).isZero();
        assertThat(output()).isEqualTo(image + "\t0.1.0\t1\n");
        assertThat(errors()).contains("Démarrage sur la version 0.1.0 (n° 1), livrée avec l'image");

        deposit("logiciel 0.2.0", "0.2.0", 2);
        assertThat(run("choisir")).isZero();
        Path installed = dir.resolve("data/mises-a-jour/versions/2/imagunity-box-0.2.0.jar");
        assertThat(output()).isEqualTo(installed + "\t0.2.0\t2\n");
        assertThat(errors()).contains("déposée : installée").contains("Passage à la version 0.2.0 (n° 2)");
        assertThat(dir.resolve("data/mises-a-jour/depot/imagunity-box-0.2.0.jar")).doesNotExist();

        // Version déposée altérée après signature : refusée, mise de côté ; la box reste sur la n° 2.
        Path tampered = deposit("logiciel 0.3.0", "0.3.0", 3);
        Files.writeString(tampered, "logiciel 0.3.0, piégé");
        assertThat(run("choisir")).isZero();
        assertThat(output()).startsWith(installed.toString());
        assertThat(errors()).contains("Version déposée refusée").contains("Artefact différent");
        assertThat(dir.resolve("data/mises-a-jour/depot/refusees/imagunity-box-0.3.0.jar")).exists();

        // Version installée altérée sur le disque : écartée ; seule reste la version de l'image, plus ancienne.
        Files.writeString(installed, "logiciel 0.2.0, piégé");
        assertThat(run("choisir")).isEqualTo(1);
        assertThat(errors()).contains("écartée").contains("Retour en arrière refusé").contains("n° 2");
    }

    @Test
    void imageWithoutAnySignedVersionDoesNotStart() throws IOException {
        publisher();
        Path jar = Files.writeString(Files.createDirectories(dir.resolve("app")).resolve("imagunity-box.jar"), "x");
        environment.put("IMAGUNITY_RELEASE_BUNDLED", jar.toString());

        assertThat(run("choisir")).isEqualTo(1);
        assertThat(errors()).contains("non signée").contains("Aucune version signée valide");
    }

    @Test
    void newerVersionsAreDownloadedFromTheUpdateServer() throws IOException {
        publisher();
        bundled("0.1.0", 1);
        Path site = Files.createDirectories(dir.resolve("site"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Path file = site.resolve(exchange.getRequestURI().getPath().replaceFirst("^/box/", ""));
            byte[] body = Files.isRegularFile(file) ? Files.readAllBytes(file) : new byte[0];
            exchange.sendResponseHeaders(Files.isRegularFile(file) ? 200 : 404, body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            environment.put("IMAGUNITY_UPDATE_URL", "http://127.0.0.1:" + server.getAddress().getPort() + "/box/");

            assertThat(run("choisir")).as("rien de publié").isZero();
            assertThat(errors()).contains("HTTP 404");

            Path jar = Files.writeString(site.resolve("imagunity-box-0.4.0.jar"), "logiciel 0.4.0");
            sign(jar, "0.4.0", 4, site.resolve("latest.json").toString());
            assertThat(run("choisir")).isZero();
            assertThat(errors()).contains("0.4.0 (n° 4) téléchargée : installée");
            assertThat(output()).endsWith("\t0.4.0\t4\n");

            assertThat(run("choisir")).isZero();
            assertThat(errors()).contains("pas de version plus récente");

            Path next = Files.writeString(site.resolve("imagunity-box-0.5.0.jar"), "logiciel 0.5.0");
            sign(next, "0.5.0", 5, site.resolve("latest.json").toString());
            Files.writeString(next, "logiciel 0.5.0, piégé sur le serveur");
            assertThat(run("choisir")).isZero();
            assertThat(errors()).contains("Mise à jour par le serveur impossible").contains("Artefact différent");
            assertThat(output()).endsWith("\t0.4.0\t4\n");

            Files.delete(next);
            assertThat(run("choisir")).isZero();
            assertThat(errors()).contains("HTTP 404 pour imagunity-box-0.5.0.jar");
        } finally {
            server.stop(0);
        }
    }
}
