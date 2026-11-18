package com.imagunity.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.CentralConfiguration;
import com.imagunity.directory.ConfigurationReport;
import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SignedDocument;
import com.imagunity.rules.PriorityRules;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Configuration centralisée appliquée par la box (données synthétiques). */
class CentralRulesTest {

    private static final HybridKeyPair ORG = HybridKeyPair.generate();
    private static final Organization ORGANIZATION = new Organization("chu-exemple", ORG);
    private static final UUID BOX = UUID.fromString("3f6c0d2e-8a8b-4b6e-9a51-0f6d3c2b1a00");

    @TempDir
    Path dir;

    /** Horloge réglable. */
    private static final class MovableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-13T10:00:00Z"));

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }

    private final MovableClock clock = new MovableClock();

    private CentralRules rules() {
        return new CentralRules(BOX, "chu-exemple", ORG.publicKey(), dir.resolve("configuration-centrale.json"),
                clock);
    }

    private SignedDocument document(long sequence, CentralConfiguration.Target target, String modality,
                                    boolean routing) {
        return ORGANIZATION.signConfiguration(CentralConfiguration.draft("chu-exemple", target, sequence,
                clock.instant(), "responsable", "essai", new CentralConfiguration.Priority(4, List.of(
                        new CentralConfiguration.Rule("thorax", 1, Map.of(modality, "CT"), null, null))),
                routing ? new CentralConfiguration.Routing(List.of(new CentralConfiguration.Rule("chu", null,
                        Map.of("InstitutionName", "CHU*"), null, List.of("box-b")))) : null));
    }

    @Test
    void theNewestDocumentForThisBoxIsAppliedAndKeptAcrossRestarts() throws Exception {
        CentralRules rules = rules();
        rules.load(); // rien d'enregistré
        assertThat(rules.priority()).isEmpty();
        assertThat(rules.routing()).isEmpty();
        assertThat(rules.snapshot().sequence()).isZero();

        SignedDocument other = document(9, CentralConfiguration.Target.of(List.of(UUID.randomUUID())), "Modality",
                true);
        SignedDocument forged = new Organization("chu-exemple", HybridKeyPair.generate()).signConfiguration(
                CentralConfiguration.draft("chu-exemple", CentralConfiguration.Target.everyone(), 8, clock.instant(),
                        null, null, new CentralConfiguration.Priority(null, List.of()), null));
        assertThat(rules.synchronize(List.of(document(2, CentralConfiguration.Target.everyone(), "Modality", false),
                document(3, CentralConfiguration.Target.of(List.of(BOX)), "Modality", true), other, forged)))
                .isTrue();
        CentralRules.Snapshot s = rules.snapshot();
        assertThat(s.sequence()).as("la plus récente qui vise cette box").isEqualTo(3);
        assertThat(s.priority()).isTrue();
        assertThat(s.routing()).isTrue();
        assertThat(s.author()).isEqualTo("responsable");
        assertThat(rules.priority()).get().satisfies(p -> {
            assertThat(p.source()).isEqualTo("configuration centrale n° 3");
            assertThat(p.rules().defaultPriority()).isEqualTo(4);
        });
        assertThat(rules.routing()).get().satisfies(r -> assertThat(r).singleElement()
                .satisfies(rule -> assertThat(rule.destinations()).containsExactly("box-b")));
        assertThat(rules.status().routingRules()).containsExactly("chu → box-b");
        assertThat(rules.status().enabled()).isTrue();

        // Plus ancien ou identique : rien ne change.
        assertThat(rules.synchronize(List.of(document(2, CentralConfiguration.Target.everyone(), "Modality",
                false)))).isFalse();
        assertThat(rules.snapshot().sequence()).isEqualTo(3);

        // Redémarrage : le document appliqué est relu et revérifié.
        CentralRules restarted = rules();
        restarted.load();
        assertThat(restarted.snapshot().sequence()).isEqualTo(3);
        assertThat(restarted.routing()).isPresent();
        Files.writeString(dir.resolve("configuration-centrale.json"), "illisible");
        CentralRules damaged = rules();
        damaged.load();
        assertThat(damaged.snapshot().sequence()).as("fichier abîmé : configuration locale").isZero();
    }

    @Test
    void aStoredDocumentThatNoLongerVerifiesAtStartupCanBeAppliedAgain() {
        Instant now = clock.now.get();
        SignedDocument fifth = document(5, CentralConfiguration.Target.everyone(), "Modality", true);
        assertThat(rules().synchronize(List.of(fifth))).isTrue();

        // Redémarrage avec l'horloge en retard (instantané de VM, horloge pas encore synchronisée) : le document
        // enregistré paraît émis dans le futur et ne se revérifie pas ; règles locales en attendant.
        clock.now.set(now.minus(Duration.ofHours(1)));
        CentralRules restarted = rules();
        restarted.load();
        assertThat(restarted.snapshot().sequence()).isZero();
        SignedDocument fourth = document(4, CentralConfiguration.Target.everyone(), "Modality", false);
        assertThat(restarted.synchronize(List.of(fourth, fifth))).as("pas de retour en arrière vers le n° 4")
                .isFalse();
        assertThat(restarted.snapshot().sequence()).isZero();

        // Horloge revenue à l'heure : le document est resté sur le disque (le n° 4 vu entre-temps ne l'a pas
        // effacé), un nouveau démarrage le reprend, et le même n° 5, toujours publié, s'applique de nouveau.
        clock.now.set(now.plus(Duration.ofMinutes(1)));
        CentralRules again = rules();
        again.load();
        assertThat(again.snapshot().sequence()).as("document gardé sur le disque").isEqualTo(5);
        assertThat(restarted.synchronize(List.of(fourth, fifth))).isTrue();
        assertThat(restarted.snapshot().sequence()).isEqualTo(5);
        assertThat(restarted.routing()).isPresent();
    }

    @Test
    void anInvalidDocumentIsRefusedAndThePreviousRulesStay() {
        CentralRules rules = rules();
        rules.synchronize(List.of(document(3, CentralConfiguration.Target.everyone(), "Modality", false)));
        // n° 5 invalide (mot-clé inconnu), n° 4 valide : la n° 4 s'applique, le refus de la n° 5 est noté.
        assertThat(rules.synchronize(List.of(document(5, CentralConfiguration.Target.everyone(), "Modalite", false),
                document(4, CentralConfiguration.Target.everyone(), "Modality", false)))).isTrue();
        CentralRules.Snapshot s = rules.snapshot();
        assertThat(s.sequence()).isEqualTo(4);
        assertThat(s.refused()).isEqualTo(5);
        assertThat(s.refusal()).contains("Modalite");
        assertThat(s.routing()).isFalse();
        assertThat(rules.routing()).as("routage local").isEmpty();
        assertThat(rules.priority()).get().satisfies(p -> assertThat(p.error()).contains("Modalite"));
        assertThat(rules.synchronize(List.of(document(5, CentralConfiguration.Target.everyone(), "Modalite",
                false)))).isFalse();
        rules.failed("Annuaire injoignable");
        assertThat(rules.snapshot().error()).isEqualTo("Annuaire injoignable");
        assertThat(rules.snapshot().sequence()).isEqualTo(4);
    }

    @Test
    void reportsAreSentOnChangeAndHourly() {
        HybridKeyPair box = HybridKeyPair.generate();
        CentralRules rules = rules();
        SignedDocument first = rules.reportIfDue(box).orElseThrow();
        ConfigurationReport report = ConfigurationReport.verify(first, id -> java.util.Optional.of(box.publicKey()));
        assertThat(report.applied()).isZero();
        rules.reported(first);
        assertThat(rules.reportIfDue(box)).as("rien de neuf").isEmpty();
        rules.synchronize(List.of(document(2, CentralConfiguration.Target.everyone(), "Modality", false)));
        SignedDocument changed = rules.reportIfDue(box).orElseThrow();
        assertThat(ConfigurationReport.verify(changed, id -> java.util.Optional.of(box.publicKey())).applied())
                .isEqualTo(2);
        rules.reported(changed);
        clock.now.set(clock.instant().plus(Duration.ofMinutes(61)));
        assertThat(rules.reportIfDue(box)).as("toutes les heures").isPresent();
    }

    @Test
    void reportsCarryTheDevicesStateAndAChangeIsReportedAtOnce() {
        HybridKeyPair box = HybridKeyPair.generate();
        CentralRules rules = rules();
        java.util.concurrent.atomic.AtomicReference<List<com.imagunity.directory.NodeCheck>> checks =
                new java.util.concurrent.atomic.AtomicReference<>(List.of(new com.imagunity.directory.NodeCheck(
                        "pacs", clock.instant(), null, null, null, null)));
        rules.nodeChecks(checks::get);
        SignedDocument first = rules.reportIfDue(box).orElseThrow();
        assertThat(ConfigurationReport.verify(first, id -> java.util.Optional.of(box.publicKey())).nodes())
                .singleElement().satisfies(c -> assertThat(c.echoOk()).isTrue());
        rules.reported(first);
        checks.set(List.of(new com.imagunity.directory.NodeCheck("pacs", clock.instant(), "x".repeat(300), null,
                null, null)));
        SignedDocument changed = rules.reportIfDue(box).orElseThrow();
        assertThat(ConfigurationReport.verify(changed, id -> java.util.Optional.of(box.publicKey())).nodes())
                .singleElement().satisfies(c -> assertThat(c.echoError()).hasSize(200));
        rules.reported(changed);
        assertThat(rules.reportIfDue(box)).isEmpty();
        java.util.List<String> order = new java.util.ArrayList<>();
        rules.synchronizeWith(new com.imagunity.directory.DirectoryClient(java.net.URI.create("http://127.0.0.1:9"),
                java.time.Duration.ofMillis(200)), box, () -> order.add("essais"));
        assertThat(order).as("annuaire injoignable : pas d'essais").isEmpty();
        assertThat(CentralRules.seen(null)).isEmpty();
        assertThat(CentralRules.seen(new com.imagunity.directory.NodeCheck("pacs", null, null, clock.instant(),
                clock.instant(), "10.9.9.9"))).contains("envoi reçu le").contains("connexion refusée depuis 10.9.9.9");
        assertThat(CentralRules.seen(checks.get().getFirst())).contains("C-ECHO en échec");
        assertThat(CentralRules.seen(new com.imagunity.directory.NodeCheck("pacs", clock.instant(), null, null,
                null, null))).contains("C-ECHO réussi");
    }

    @Test
    void centralPriorityRulesOverrideTheLocalOnes() {
        ReloadablePriority priority = ReloadablePriority.fixed(new PriorityRules(6, List.of()), clock);
        CentralRules rules = rules();
        priority.central(rules::priority);
        assertThat(priority.snapshot().source()).isEqualTo("aucune règle");
        rules.synchronize(List.of(document(2, CentralConfiguration.Target.everyone(), "Modality", false)));
        assertThat(priority.snapshot().source()).isEqualTo("configuration centrale n° 2");
        assertThat(priority.snapshot().rules().defaultPriority()).isEqualTo(4);
    }

    @Test
    void theDirectoryLoopReadsAppliesAndReports() throws Exception {
        HybridKeyPair boxKeys = HybridKeyPair.generate();
        String published = "{\"organization\":\"chu-exemple\",\"configurations\":["
                + document(6, CentralConfiguration.Target.everyone(), "Modality", true).toJson() + "]}";
        List<String> reports = new CopyOnWriteArrayList<>();
        int[] reportStatus = {201};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/configurations", exchange -> {
            if (exchange.getRequestMethod().equals("POST")) {
                reports.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                exchange.sendResponseHeaders(reportStatus[0], -1);
            } else {
                byte[] body = published.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        DirectoryClient client = new DirectoryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(5));
        CentralRules rules = rules();
        try {
            rules.synchronizeWith(client, boxKeys);
            assertThat(rules.snapshot().sequence()).isEqualTo(6);
            assertThat(reports).hasSize(1);
            rules.synchronizeWith(client, boxKeys);
            assertThat(reports).as("rien de neuf : pas de nouveau compte rendu").hasSize(1);
            clock.now.set(clock.instant().plus(Duration.ofHours(2)));
            reportStatus[0] = 503;
            rules.synchronizeWith(client, boxKeys);
            assertThat(reports).hasSize(2);
        } finally {
            server.stop(0);
        }
        rules.synchronizeWith(client, boxKeys);
        assertThat(rules.snapshot().error()).contains("injoignable");
        assertThat(rules.snapshot().sequence()).isEqualTo(6);
    }

    @Test
    void groupsTargetTheBoxAndLeavingAGroupFallsBackToWhatStillTargetsIt() {
        java.util.concurrent.atomic.AtomicReference<java.util.Collection<String>> groups =
                new java.util.concurrent.atomic.AtomicReference<>(List.of("imagerie-nord"));
        Path file = dir.resolve("groupes.json");
        CentralRules rules = new CentralRules(BOX, "chu-exemple", ORG.publicKey(), file, clock, groups::get);
        SignedDocument everyone = document(4, CentralConfiguration.Target.everyone(), "Modality", false);
        SignedDocument nord = document(5, CentralConfiguration.Target.groups(List.of("imagerie-nord")), "Modality",
                true);
        SignedDocument sud = document(6, CentralConfiguration.Target.groups(List.of("imagerie-sud")), "Modality",
                false);
        assertThat(rules.synchronize(List.of(everyone, nord, sud))).isTrue();
        assertThat(rules.snapshot().sequence()).as("la plus récente qui vise la box ou son groupe").isEqualTo(5);
        assertThat(rules.status().groups()).containsExactly("imagerie-nord");

        // Le responsable retire la box du groupe : la n° 4 (toute l'organisation) la vise encore.
        groups.set(List.of());
        assertThat(rules.synchronize(List.of(everyone, nord, sud))).isTrue();
        assertThat(rules.snapshot().sequence()).isEqualTo(4);
        assertThat(rules.routing()).as("routage de la n° 5 abandonné").isEmpty();

        // Seuls des documents de groupes : hors du groupe, configuration locale.
        Path other = dir.resolve("groupes-seuls.json");
        groups.set(List.of("imagerie-nord"));
        CentralRules onlyGroups = new CentralRules(BOX, "chu-exemple", ORG.publicKey(), other, clock, groups::get);
        assertThat(onlyGroups.synchronize(List.of(nord, sud))).isTrue();
        groups.set(List.of());
        assertThat(onlyGroups.synchronize(List.of(nord, sud))).isTrue();
        assertThat(onlyGroups.snapshot().sequence()).isZero();
        assertThat(onlyGroups.priority()).isEmpty();
        assertThat(onlyGroups.synchronize(List.of(nord, sud))).isFalse();

        // Rejointe plus tard : la n° 5, déjà appliquée, n'est pas réappliquée (jamais de retour en arrière), même
        // après un redémarrage ; une version plus récente l'est.
        CentralRules restarted = new CentralRules(BOX, "chu-exemple", ORG.publicKey(), other, clock, groups::get);
        restarted.load();
        groups.set(List.of("imagerie-nord"));
        assertThat(restarted.synchronize(List.of(nord))).isFalse();
        assertThat(restarted.snapshot().sequence()).isZero();
        SignedDocument nord7 = document(7, CentralConfiguration.Target.groups(List.of("imagerie-nord")), "Modality",
                false);
        assertThat(restarted.synchronize(List.of(nord, nord7))).isTrue();
        assertThat(restarted.snapshot().sequence()).isEqualTo(7);
    }

    @Test
    void theFallbackUsesTheDocumentsAlreadySeenEvenIfTheDirectoryHidesThem() {
        java.util.concurrent.atomic.AtomicReference<java.util.Collection<String>> groups =
                new java.util.concurrent.atomic.AtomicReference<>(List.of("imagerie-nord"));
        Path file = dir.resolve("repli.json");
        CentralRules rules = new CentralRules(BOX, "chu-exemple", ORG.publicKey(), file, clock, groups::get);
        SignedDocument old = document(2, CentralConfiguration.Target.everyone(), "Modality", false);
        SignedDocument recent = document(3, CentralConfiguration.Target.everyone(), "BodyPartExamined", false);
        SignedDocument nord = document(5, CentralConfiguration.Target.groups(List.of("imagerie-nord")), "Modality",
                true);
        assertThat(rules.synchronize(List.of(old, recent, nord))).isTrue();
        assertThat(rules.snapshot().sequence()).isEqualTo(5);

        // Box retirée du groupe ; l'annuaire (compromis) cache la n° 3 pour imposer la n° 2. Après un redémarrage.
        CentralRules restarted = new CentralRules(BOX, "chu-exemple", ORG.publicKey(), file, clock, groups::get);
        restarted.load();
        groups.set(List.of());
        assertThat(restarted.synchronize(List.of(old, nord))).isTrue();
        assertThat(restarted.snapshot().sequence()).as("la plus récente déjà vue qui vise la box").isEqualTo(3);
    }

    @Test
    void onlyTheMostRecentDocumentsAreRemembered() throws Exception {
        CentralRules rules = rules();
        List<SignedDocument> documents = new java.util.ArrayList<>();
        for (int i = 1; i <= CentralRules.REMEMBERED + 3; i++) {
            documents.add(document(i, CentralConfiguration.Target.of(List.of(UUID.randomUUID())), "Modality",
                    false));
        }
        assertThat(rules.synchronize(documents)).as("aucun ne vise cette box").isFalse();
        assertThat(rules.synchronize(documents.subList(0, 2))).as("trop anciens pour être gardés").isFalse();
        tools.jackson.databind.JsonNode stored = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(java.nio.file.Files.readString(dir.resolve("configuration-centrale.json")));
        assertThat(stored.get("seen")).hasSize(CentralRules.REMEMBERED);
        java.nio.file.Files.writeString(dir.resolve("configuration-centrale.json"),
                "{\"floor\":0,\"seen\":[\"illisible\"]}");
        CentralRules reloaded = rules();
        reloaded.load();
        assertThat(reloaded.snapshot().sequence()).isZero();
    }

    private SignedDocument dicomDocument(long sequence, String keyword) {
        UUID b = UUID.fromString("00000000-0000-0000-0000-00000000000b");
        CentralConfiguration.Dicom dicom = new CentralConfiguration.Dicom("IMAGUNITY_NORD", List.of(
                new CentralConfiguration.Node("pacs-nord", "PACS_NORD", "10.2.0.15", 104, true, true),
                new CentralConfiguration.Node("ct-urg", "CT_URG", null, null, true, false)),
                List.of(new CentralConfiguration.Route("ct-vers-b", List.of("ct-urg"), "IU_TO_B", Map.of(keyword, "CT"),
                        null, List.of(b.toString()))),
                List.of(new CentralConfiguration.Route("tout", List.of(b.toString()), null, null,
                        Map.of("Modality", "MR"), List.of("pacs-nord"))));
        return ORGANIZATION.signConfiguration(CentralConfiguration.draft("chu-exemple",
                CentralConfiguration.Target.of(List.of(BOX)), sequence, clock.instant(), null, null, null, null, dicom));
    }

    @Test
    void aDicomBlockIsCompiledAppliedAndShownWithoutTouchingTheRules() throws Exception {
        CentralRules rules = rules();
        assertThat(rules.dicom()).isEmpty();
        assertThat(rules.synchronize(List.of(dicomDocument(1, "Modality")))).isTrue();
        assertThat(rules.dicom()).hasValueSatisfying(d -> {
            assertThat(d.aet()).isEqualTo("IMAGUNITY_NORD");
            assertThat(d.outgoing()).hasSize(1);
            assertThat(d.incoming()).hasSize(1);
        });
        assertThat(rules.priority()).as("bloc absent : règles locales").isEmpty();
        assertThat(rules.routing()).isEmpty();
        assertThat(rules.status().dicom()).containsExactly("AET de la box : IMAGUNITY_NORD",
                "Nœud ct-urg (CT_URG) : source", "Nœud pacs-nord (PACS_NORD, 10.2.0.15:104) : source et destination",
                "Route sortante ct-vers-b : CT_URG vers IU_TO_B [Modality=CT] → 1 box",
                "Route entrante tout : 1 box [ou (Modality=MR)] → pacs-nord");

        // Filtre au mot-clé inconnu : refusé, la version en vigueur reste.
        assertThat(rules.synchronize(List.of(dicomDocument(2, "Modalite")))).isFalse();
        assertThat(rules.snapshot().refused()).isEqualTo(2);
        assertThat(rules.snapshot().refusal()).contains("Modalite");
        assertThat(rules.snapshot().sequence()).isEqualTo(1);

        CentralRules reloaded = rules();
        reloaded.load();
        assertThat(reloaded.dicom()).isPresent();
        assertThat(CentralRules.dicom(null)).isNull();
        assertThat(CentralRules.describe(com.imagunity.rules.DicomRouting.compile(null, List.of(new com.imagunity
                .rules.DicomRouting.NodeSpec("pacs", "PACS", "h", null, false, true)), List.of(new com.imagunity
                .rules.DicomRouting.RouteSpec("r", List.of(), null, Map.of(), Map.of(), List.of(UUID.randomUUID()
                .toString()))), List.of(new com.imagunity.rules.DicomRouting.RouteSpec("e", List.of(), null, Map.of(),
                Map.of(), List.of("pacs")))))).containsExactly("Nœud pacs (PACS, h) : destination",
                "Route sortante r : toute source → 1 box", "Route entrante e : toute box → pacs");
    }
}
