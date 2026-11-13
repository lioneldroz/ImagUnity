package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Documents de configuration centralisée et comptes rendus des box (données synthétiques). */
class CentralConfigurationTest {

    private static final HybridKeyPair ORG = HybridKeyPair.generate();
    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID BOX = UUID.fromString("3f6c0d2e-8a8b-4b6e-9a51-0f6d3c2b1a00");

    @TempDir
    Path dir;

    private static CentralConfiguration.Priority priority() {
        return new CentralConfiguration.Priority(5, List.of(new CentralConfiguration.Rule("thorax", 1,
                Map.of("Modality", "CT", "SeriesDescription", "*THORAX*"), null, null)));
    }

    private static CentralConfiguration.Routing routing() {
        return new CentralConfiguration.Routing(List.of(new CentralConfiguration.Rule("chu", null,
                Map.of("InstitutionName", "CHU*"), Map.of(), List.of("box-b"))));
    }

    private static CentralConfiguration draft(CentralConfiguration.Target target, long sequence) {
        return CentralConfiguration.draft("chu-exemple", target, sequence, NOW, "responsable", "essai", priority(),
                routing());
    }

    @Test
    void aSignedConfigurationIsVerifiedAgainstThePinnedKey() {
        Organization organization = new Organization("chu-exemple", ORG);
        CentralConfiguration draft = draft(CentralConfiguration.Target.everyone(), 3);
        SignedDocument signed = organization.signConfiguration(draft);
        CentralConfiguration verified = CentralConfiguration.verify(signed, "chu-exemple", ORG.publicKey(), CLOCK);
        assertThat(verified).isEqualTo(draft);
        assertThat(verified.target().includes(BOX)).isTrue();
        assertThat(verified.describe()).contains("n° 3").contains("priorité : 1 règle").contains("routage : 1 règle");
        assertThat(CentralConfiguration.fromJson(draft.toJson())).isEqualTo(draft);

        assertThatThrownBy(() -> CentralConfiguration.verify(signed, "chu-exemple", HybridKeyPair.generate()
                .publicKey(), CLOCK)).hasMessageContaining("non signée");
        assertThatThrownBy(() -> CentralConfiguration.verify(signed, "chu-voisin", ORG.publicKey(), CLOCK))
                .hasMessageContaining("autre organisation");
        assertThatThrownBy(() -> CentralConfiguration.verify(signed, "chu-exemple", ORG.publicKey(),
                Clock.fixed(NOW.minus(Duration.ofHours(1)), ZoneOffset.UTC))).hasMessageContaining("futur");
        assertThatThrownBy(() -> new Organization("chu-voisin", ORG).signConfiguration(draft))
                .hasMessageContaining("responsable de « chu-voisin »");
        // Une entrée de box signée ne passe pas pour une configuration, et inversement.
        SignedDocument entry = organization.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), BOX, "B",
                Map.of(), NOW), NOW, Duration.ofDays(1), 1);
        assertThatThrownBy(() -> CentralConfiguration.verify(entry, "chu-exemple", ORG.publicKey(), CLOCK))
                .isInstanceOf(DirectoryException.class);
    }

    @Test
    void malformedDocumentsAreRefused() {
        CentralConfiguration.Target some = CentralConfiguration.Target.of(List.of(BOX));
        assertThat(some.includes(BOX)).isTrue();
        assertThat(some.includes(UUID.randomUUID())).isFalse();
        assertThatThrownBy(() -> CentralConfiguration.Target.of(List.of())).hasMessageContaining("aucune box");
        assertThatThrownBy(() -> new CentralConfiguration.Target(true, List.of(BOX))).hasMessageContaining("à la fois");
        assertThatThrownBy(() -> CentralConfiguration.Target.of(List.of(BOX, BOX))).hasMessageContaining("double");
        assertThatThrownBy(() -> draft(some, 0)).hasMessageContaining("numéro");
        assertThatThrownBy(() -> draft(some, CentralConfiguration.MAX_SEQUENCE + 1)).hasMessageContaining("numéro");
        assertThat(draft(some, CentralConfiguration.MAX_SEQUENCE).sequence()).isEqualTo(CentralConfiguration.MAX_SEQUENCE);
        assertThatThrownBy(() -> CentralConfiguration.draft("chu-exemple", some, 1, NOW, null, null, null, null))
                .hasMessageContaining("vide");
        assertThatThrownBy(() -> CentralConfiguration.draft("chu-exemple", some, 1, NOW, null, null,
                new CentralConfiguration.Priority(0, List.of()), null)).hasMessageContaining("par défaut");
        assertThatThrownBy(() -> CentralConfiguration.draft("chu-exemple", some, 1, NOW, null, null,
                new CentralConfiguration.Priority(null, List.of(rule("a b", 1, null))), null))
                .hasMessageContaining("nom invalide");
        assertThatThrownBy(() -> CentralConfiguration.draft("chu-exemple", some, 1, NOW, null, null,
                new CentralConfiguration.Priority(null, List.of(rule("a", 1, null), rule("a", 2, null))), null))
                .hasMessageContaining("double");
        assertThatThrownBy(() -> CentralConfiguration.draft("chu-exemple", some, 1, NOW, null, null,
                new CentralConfiguration.Priority(null, List.of(rule("a", 10, null))), null))
                .hasMessageContaining("hors de 1 à 9");
        assertThatThrownBy(() -> CentralConfiguration.draft("chu-exemple", some, 1, NOW, null, null,
                new CentralConfiguration.Priority(null, List.of(rule("a", 1, List.of("box-b")))), null))
                .hasMessageContaining("réservé au routage");
        assertThatThrownBy(() -> CentralConfiguration.draft("chu-exemple", some, 1, NOW, null, null, null,
                new CentralConfiguration.Routing(List.of(rule("a", null, null))))).hasMessageContaining("aucun partenaire");
        assertThatThrownBy(() -> CentralConfiguration.draft("chu-exemple", some, 1, NOW, null, null, null,
                new CentralConfiguration.Routing(List.of(rule("a", 3, List.of("b")))))).hasMessageContaining("réservé");
        String json = draft(some, 1).toJson();
        assertThatThrownBy(() -> CentralConfiguration.fromJson(json.replace(CentralConfiguration.TYPE, "autre")))
                .hasMessageContaining("type");
        assertThatThrownBy(() -> CentralConfiguration.fromJson(json.replace("\"IU1\"", "\"IU9\"")))
                .hasMessageContaining("suite");
        assertThat(new CentralConfiguration.Priority(null, null).rules()).isEmpty();
        assertThat(new CentralConfiguration.Routing(null).rules()).isEmpty();
        assertThat(draft(some, 2).describe()).contains("n° 2");
        assertThat(CentralConfiguration.draft("chu-exemple", some, 1, NOW, null, null, null, routing()).describe())
                .doesNotContain("priorité");
    }

    private static CentralConfiguration.Rule rule(String name, Integer priority, List<String> to) {
        return new CentralConfiguration.Rule(name, priority, Map.of("Modality", "CT"), null, to);
    }

    @Test
    void reportsAreSignedByTheBox() {
        HybridKeyPair box = HybridKeyPair.generate();
        SignedDocument report = ConfigurationReport.of(BOX, 3, NOW, 4, "règle invalide".repeat(60), NOW).sign(box);
        ConfigurationReport verified = ConfigurationReport.verify(report, id -> id.equals(BOX)
                ? Optional.of(box.publicKey()) : Optional.empty());
        assertThat(verified.applied()).isEqualTo(3);
        assertThat(verified.refusal()).hasSize(500);
        assertThatThrownBy(() -> ConfigurationReport.verify(report, id -> Optional.empty()))
                .hasMessageContaining("inconnue");
        assertThatThrownBy(() -> ConfigurationReport.verify(report, id -> Optional.of(HybridKeyPair.generate()
                .publicKey()))).hasMessageContaining("non signé");
        SignedDocument other = SignedDocument.sign(report.payload().replace(ConfigurationReport.TYPE, "autre"), box);
        assertThatThrownBy(() -> ConfigurationReport.verify(other, id -> Optional.of(box.publicKey())))
                .hasMessageContaining("type");
    }

    @Test
    void theAdminToolSignsAndPublishesConfigurations() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Map<String, String> environment = Map.of(DirectoryAdmin.ORGANIZATION_PASSPHRASE, "phrase du responsable",
                DirectoryAdmin.ACCOUNT_VARIABLE, "responsable:secret");
        DirectoryAdmin admin = new DirectoryAdmin(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), CLOCK, environment);
        String keys = dir.resolve("org.json").toString();
        assertThat(admin.run(new String[] {"organisation-creer", "--organisation", "chu-exemple", "--cles", keys}))
                .isZero();
        Files.writeString(dir.resolve("brouillon.json"), draft(CentralConfiguration.Target.of(List.of(BOX)), 7)
                .toJson());
        Files.writeString(dir.resolve("brouillon-tous.json"), CentralConfiguration.draft("chu-exemple",
                CentralConfiguration.Target.everyone(), 8, NOW, null, null, new CentralConfiguration.Priority(null,
                        List.of()), null).toJson());
        out.reset();
        assertThat(admin.run(new String[] {"configuration-signer", "--cles", keys, "--brouillon",
                dir.resolve("brouillon.json").toString(), "--sortie", dir.resolve("refus.json").toString()}))
                .as("sans confirmation").isEqualTo(1);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("abandonnée");
        assertThat(dir.resolve("refus.json")).doesNotExist();
        DirectoryAdmin confirming = new DirectoryAdmin(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8), CLOCK, environment,
                label -> label.startsWith("Signer") ? " OUI ".toCharArray() : null);
        assertThat(confirming.run(new String[] {"configuration-signer", "--cles", keys, "--brouillon",
                dir.resolve("brouillon.json").toString(), "--sortie", dir.resolve("config.json").toString()})).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("n° 7").contains("1 box").contains("thorax (1)")
                .contains("chu → [box-b]").contains("Document exact").contains("\"InstitutionName\"");
        assertThat(admin.run(new String[] {"configuration-signer", "--cles", keys, "--brouillon",
                dir.resolve("brouillon-tous.json").toString(), "--sortie", dir.resolve("tous.json").toString(),
                "--confirmer"})).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("toute l'organisation").contains("par défaut 5");
        KeyFiles.OrganizationPublicKey key = KeyFiles.readOrganizationPublic(Path.of(keys + ".pub.json"));
        SignedDocument signed = SignedDocument.fromJson(Files.readString(dir.resolve("config.json")));
        assertThat(CentralConfiguration.verify(signed, "chu-exemple", key.key(), CLOCK).sequence()).isEqualTo(7);

        java.util.List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        int[] status = {201};
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/configurations", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status[0], -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort();
            String[] publish = {"configuration-publier", "--annuaire", url, "--configuration",
                    dir.resolve("config.json").toString()};
            assertThat(admin.run(publish)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("Configuration publiée");
            status[0] = 200;
            assertThat(admin.run(publish)).isZero();
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("déjà publiée");
            status[0] = 409;
            assertThat(admin.run(publish)).isEqualTo(1);
            assertThat(err.toString(StandardCharsets.UTF_8)).contains("HTTP 409");
            assertThat(received).hasSize(3).allSatisfy(body -> assertThat(body).contains("\"payload\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void groupsAreSignedInTheEntryAndTargeted() {
        Organization organization = new Organization("chu-exemple", ORG);
        SignedDocument request = EnrollmentRequest.create(HybridKeyPair.generate(), BOX, "B", Map.of(), NOW);
        SignedDocument plain = organization.enroll(request, NOW, Duration.ofDays(1), 1);
        assertThat(plain.payload()).as("entrée sans groupe inchangée").doesNotContain("groups");
        SignedDocument grouped = organization.enroll(request, NOW, Duration.ofDays(1), 2,
                List.of("urgences", "imagerie-nord"));
        BoxEntry entry = Json.read(grouped.payload(), BoxEntry.class, "Entrée");
        assertThat(entry.groups()).containsExactly("imagerie-nord", "urgences");
        assertThat(Json.read(organization.revoke(grouped, NOW, "essai").payload(), BoxEntry.class, "Entrée")
                .groups()).as("gardés à la révocation").containsExactly("imagerie-nord", "urgences");
        assertThatThrownBy(() -> organization.enroll(request, NOW, Duration.ofDays(1), 3, List.of("Majuscules")))
                .hasMessageContaining("invalide");
        assertThatThrownBy(() -> BoxEntry.groups(List.of("a", "a"))).hasMessageContaining("double");
        assertThatThrownBy(() -> BoxEntry.groups(java.util.stream.IntStream.range(0, 17).mapToObj(i -> "g" + i)
                .toList())).hasMessageContaining("Trop");

        CentralConfiguration.Target target = CentralConfiguration.Target.groups(List.of("imagerie-nord"));
        assertThat(target.includes(BOX, entry.groups())).isTrue();
        assertThat(target.includes(BOX, List.of("urgences-sud"))).isFalse();
        assertThat(target.includes(BOX)).isFalse();
        assertThat(target.describe()).isEqualTo("groupes imagerie-nord");
        CentralConfiguration.Target both = new CentralConfiguration.Target(false, List.of(BOX), List.of("urgences"));
        assertThat(both.describe()).contains("1 box").contains("groupes urgences");
        assertThat(CentralConfiguration.Target.everyone().describe()).isEqualTo("toute l'organisation");
        assertThatThrownBy(() -> new CentralConfiguration.Target(true, List.of(), List.of("urgences")))
                .hasMessageContaining("à la fois");
        assertThatThrownBy(() -> CentralConfiguration.Target.groups(List.of("Pas Valide")))
                .isInstanceOf(DirectoryException.class).hasMessageContaining("groupe");
        CentralConfiguration grouped2 = CentralConfiguration.draft("chu-exemple", target, 4, NOW, null, null,
                priority(), null);
        assertThat(CentralConfiguration.fromJson(grouped2.toJson()).target().groups()).containsExactly("imagerie-nord");
    }

    @Test
    void theAdminToolEnrollsIntoGroups() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DirectoryAdmin admin = new DirectoryAdmin(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), CLOCK,
                Map.of(DirectoryAdmin.ORGANIZATION_PASSPHRASE, "phrase du responsable",
                        DirectoryAdmin.BOX_PASSPHRASE, "phrase de la box de test"));
        String keys = dir.resolve("org.json").toString();
        assertThat(admin.run(new String[] {"organisation-creer", "--organisation", "chu-exemple", "--cles", keys}))
                .isZero();
        assertThat(admin.run(new String[] {"box-demande", "--cles", dir.resolve("box.json").toString(), "--box-id",
                BOX.toString(), "--nom", "B", "--sortie", dir.resolve("d.json").toString()})).isZero();
        assertThat(admin.run(new String[] {"enroler", "--cles", keys, "--demande", dir.resolve("d.json").toString(),
                "--groupes", "urgences, imagerie-nord,", "--sortie", dir.resolve("e.json").toString()})).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("groupes [imagerie-nord, urgences]");
        assertThat(Files.readString(dir.resolve("e.json"))).contains("imagerie-nord");
        assertThat(admin.run(new String[] {"enroler", "--cles", keys, "--demande", dir.resolve("d.json").toString(),
                "--groupes", "Mauvais Nom", "--sortie", dir.resolve("f.json").toString()})).isEqualTo(1);
    }
}
