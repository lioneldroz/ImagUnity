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

    private int run(String... args) {
        return new DirectoryAdmin(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), Clock.fixed(now, ZoneOffset.UTC)).run(args);
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
}
