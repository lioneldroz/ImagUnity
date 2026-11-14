package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Outil du responsable : codes d'enrôlement, enrôlement des demandes en attente, renouvellement (annuaire simulé). */
class AutomaticEnrollmentAdminTest {

    private static final String ACCOUNT = "responsable:depot-essai";

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private Instant now = Instant.parse("2026-11-14T10:00:00Z");
    private final Deque<String> typed = new ArrayDeque<>();
    private Map<String, String> environment = Map.of(DirectoryAdmin.ORGANIZATION_PASSPHRASE, "phrase du responsable",
            DirectoryAdmin.ACCOUNT_VARIABLE, ACCOUNT);

    /** Annuaire simulé : demandes en attente, entrées publiées, statut des dépôts. */
    private HttpServer server;
    private final List<String> pending = new CopyOnWriteArrayList<>();
    private final Map<UUID, SignedDocument> published = new ConcurrentHashMap<>();
    private final List<String> accounts = new CopyOnWriteArrayList<>();
    private int publishStatus = 201;
    private int listStatus = 200;
    private String url;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            accounts.add(auth == null ? "" : new String(java.util.Base64.getDecoder().decode(auth.substring(6)),
                    StandardCharsets.UTF_8));
            int status;
            String body = "";
            if (path.equals("/directory/v1/enrollment-requests")) {
                status = listStatus;
                body = "[" + String.join(",", pending) + "]";
            } else if (path.equals("/directory/v1/entries") && exchange.getRequestMethod().equals("POST")) {
                SignedDocument entry = SignedDocument.fromJson(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                status = publishStatus;
                if (status == 201) {
                    published.put(Json.read(entry.payload(), BoxEntry.class, "entrée").boxId(), entry);
                }
            } else if (path.equals("/directory/v1/entries")) {
                status = listStatus;
                body = "{\"organization\":\"chu-exemple\",\"entries\":[" + String.join(",",
                        published.values().stream().map(SignedDocument::toJson).toList()) + "]}";
            } else {
                SignedDocument entry = published.get(UUID.fromString(path.substring(path.lastIndexOf('/') + 1)));
                status = entry == null ? 404 : 200;
                body = entry == null ? "" : entry.toJson();
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
                new PrintStream(err, true, StandardCharsets.UTF_8), Clock.fixed(now, ZoneOffset.UTC), environment,
                label -> typed.isEmpty() ? null : typed.poll().toCharArray()).run(args);
    }

    private String p(String name) {
        return dir.resolve(name).toString();
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8);
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    private String createCode(String name, String... extra) {
        java.util.List<String> args = new java.util.ArrayList<>(List.of("code-creer", "--codes", p("codes.json"),
                "--nom", name));
        args.addAll(List.of(extra));
        assertThat(run(args.toArray(String[]::new))).isZero();
        String line = output().lines().filter(l -> l.contains("IMAGUNITY_DIRECTORY_ENROLLMENT_CODE=")).findFirst()
                .orElseThrow();
        return line.substring(line.indexOf('=') + 1);
    }

    /** Une box qui envoie sa demande à l'annuaire, avec ou sans code. */
    private UUID boxSubmits(String proposedName, String code) {
        UUID box = UUID.randomUUID();
        SignedDocument request = EnrollmentRequest.create(HybridKeyPair.generate(),
                HybridKemKeyPair.generate().publicKey(), box, proposedName, Map.of(), now);
        pending.add("{\"boxId\":\"" + box + "\",\"submission\":" + EnrollmentSubmission.of(request, code).toJson()
                + "}");
        return box;
    }

    private BoxEntry entry(UUID box) {
        return Json.read(published.get(box).payload(), BoxEntry.class, "entrée");
    }

    @Test
    void codesAreKeptOnTheResponsiblePostAndBoxesWithACodeAreEnrolledAutomatically() throws IOException {
        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json"))).isZero();
        String nord = createCode("CHU Exemple — imagerie Nord", "--groupes", "imagerie-nord,urgences");
        assertThat(output()).contains("valable jusqu'au 2026-12-14").contains("[imagerie-nord, urgences]");
        String sud = createCode("CHU Exemple — imagerie Sud", "--validite-jours", "7");
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(
                dir.resolve("codes.json")))).as("codes : propriétaire seul").isEqualTo("rw-------");

        UUID boxNord = boxSubmits("nom proposé par la box", nord);
        UUID boxSud = boxSubmits("CHU Exemple — imagerie Sud", sud.toLowerCase());
        UUID sansCode = boxSubmits("Box sans code", null);
        UUID inconnue = boxSubmits("Box au code inconnu", EnrollmentCode.generate());

        typed.add("oui");
        typed.add("non");
        assertThat(run("enroler-attente", "--cles", p("org.json"), "--codes", p("codes.json"), "--annuaire", url))
                .isZero();
        assertThat(output()).contains("Box « CHU Exemple — imagerie Nord » (nom proposé par la box : « nom proposé "
                        + "par la box »)").contains("code d'enrôlement vérifié")
                .contains("Box sans code").contains("sans code d'enrôlement de ce responsable")
                .contains("Box au code inconnu").contains("Laissée en attente")
                .contains("1 box enrôlée(s), 3 demande(s) laissée(s) de côté");
        assertThat(entry(boxNord).displayName()).isEqualTo("CHU Exemple — imagerie Nord");
        assertThat(entry(boxNord).groups()).containsExactly("imagerie-nord", "urgences");
        assertThat(entry(boxNord).serial()).isEqualTo(1);
        assertThat(published).doesNotContainKeys(boxSud, sansCode, inconnue);
        assertThat(accounts).contains(ACCOUNT);

        // Le code de la box Nord est marqué utilisé : une autre box qui le présenterait est refusée.
        assertThat(Files.readString(dir.resolve("codes.json"))).contains(boxNord.toString());
        pending.clear();
        UUID copie = boxSubmits("Copie", nord);
        boxSubmits("CHU Exemple — imagerie Sud", sud);
        assertThat(run("enroler-attente", "--cles", p("org.json"), "--codes", p("codes.json"), "--annuaire", url,
                "--confirmer")).isZero();
        assertThat(output()).contains("code déjà utilisé par la box " + boxNord).contains("Enrôlée et publiée");
        assertThat(published).doesNotContainKey(copie);
    }

    @Test
    void invalidProofsExpiredCodesAndRefusalsAreReported() throws IOException {
        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json"))).isZero();
        String code = createCode("Box d'essai", "--validite-jours", "1");

        // Preuve falsifiée : identifiant du bon code, preuve d'une autre demande.
        UUID box = UUID.randomUUID();
        SignedDocument request = EnrollmentRequest.create(HybridKeyPair.generate(), null, box, "Box d'essai",
                Map.of(), now);
        SignedDocument other = EnrollmentRequest.create(HybridKeyPair.generate(), null, box, "Autre", Map.of(), now);
        EnrollmentSubmission forged = new EnrollmentSubmission(request, EnrollmentCode.id(code),
                EnrollmentCode.proof(code, other));
        pending.add("{\"submission\":" + forged.toJson() + "}");
        // Demande dont la signature ne correspond pas aux clés présentées.
        pending.add("{\"submission\":" + new EnrollmentSubmission(new SignedDocument(request.payload(),
                other.signatures()), null, null).toJson() + "}");
        assertThat(run("enroler-attente", "--cles", p("org.json"), "--codes", p("codes.json"), "--annuaire", url,
                "--confirmer")).isZero();
        assertThat(output()).contains("preuve du code invalide");
        assertThat(errors()).contains("illisible ou mal signée");

        // Code expiré.
        pending.clear();
        boxSubmits("Box d'essai", code);
        now = now.plus(Duration.ofDays(2));
        assertThat(run("enroler-attente", "--cles", p("org.json"), "--codes", p("codes.json"), "--annuaire", url,
                "--confirmer")).isZero();
        assertThat(output()).contains("code expiré le 2026-11-15");

        // Publication refusée, lecture refusée, compte absent.
        String fresh = createCode("Box d'essai");
        pending.clear();
        boxSubmits("Box d'essai", fresh);
        publishStatus = 409;
        assertThat(run("enroler-attente", "--cles", p("org.json"), "--codes", p("codes.json"), "--annuaire", url,
                "--confirmer")).isZero();
        assertThat(errors()).contains("Publication refusée (HTTP 409)");
        listStatus = 401;
        assertThat(run("enroler-attente", "--cles", p("org.json"), "--codes", p("codes.json"), "--annuaire", url))
                .isEqualTo(1);
        assertThat(run("renouveler", "--cles", p("org.json"), "--annuaire", url)).isEqualTo(1);
        environment = Map.of(DirectoryAdmin.ORGANIZATION_PASSPHRASE, "phrase du responsable");
        assertThat(run("enroler-attente", "--cles", p("org.json"), "--codes", p("codes.json"), "--annuaire", url))
                .isEqualTo(2);
        assertThat(errors()).contains("Compte de dépôt manquant");

        assertThat(run("code-creer", "--codes", p("codes.json"), "--nom", " ")).as("nom absent").isEqualTo(2);
        assertThat(run("code-creer", "--codes", p("codes.json"), "--nom", "x".repeat(257))).isEqualTo(1);
        assertThat(run("code-creer", "--codes", p("codes.json"), "--nom", "x", "--validite-jours", "0")).isEqualTo(1);
        assertThat(run("code-creer", "--codes", p("codes.json"), "--nom", "x", "--groupes", "Pas Valide"))
                .isEqualTo(1);
    }

    @Test
    void entriesThatExpireSoonAreRenewedTogether() throws IOException {
        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json"))).isZero();
        Organization organization = new Organization("chu-exemple",
                KeyFiles.readPrivate(dir.resolve("org.json"), "phrase du responsable".toCharArray()));
        UUID soon = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        UUID revoked = UUID.randomUUID();
        for (UUID box : List.of(soon, later, revoked)) {
            SignedDocument request = EnrollmentRequest.create(HybridKeyPair.generate(), null, box, "Box " + box,
                    Map.of(), now);
            Duration validity = box.equals(later) ? Duration.ofDays(90) : Duration.ofDays(10);
            SignedDocument entry = organization.enroll(request, now, validity, 1, List.of("imagerie-nord"));
            published.put(box, box.equals(revoked) ? organization.revoke(entry, now, "essai") : entry);
        }
        // Entrée d'un autre responsable : ignorée.
        SignedDocument foreign = new Organization("chu-exemple", HybridKeyPair.generate()).enroll(
                EnrollmentRequest.create(HybridKeyPair.generate(), null, UUID.randomUUID(), "Intruse", Map.of(), now),
                now, Duration.ofDays(5), 1);
        published.put(UUID.randomUUID(), foreign);

        typed.add("non");
        assertThat(run("renouveler", "--cles", p("org.json"), "--annuaire", url)).isEqualTo(1);
        assertThat(errors()).contains("abandonné");
        assertThat(run("renouveler", "--cles", p("org.json"), "--annuaire", url, "--confirmer")).isZero();
        assertThat(output()).contains("expire le 2026-11-24").contains("1 entrée(s) renouvelée(s)")
                .doesNotContain("Intruse");
        assertThat(entry(soon).serial()).isEqualTo(2);
        assertThat(entry(soon).validUntil()).isEqualTo(now.plus(Duration.ofDays(90)));
        assertThat(entry(soon).groups()).containsExactly("imagerie-nord");
        assertThat(entry(later).serial()).isEqualTo(1);

        assertThat(run("renouveler", "--cles", p("org.json"), "--annuaire", url, "--avant-jours", "1")).isZero();
        assertThat(output()).contains("Aucune entrée n'expire");

        now = now.plus(Duration.ofDays(85));
        publishStatus = 500;
        assertThat(run("renouveler", "--cles", p("org.json"), "--annuaire", url, "--confirmer")).isEqualTo(1);
        assertThat(errors()).contains("Publication refusée (HTTP 500)");
    }

    @Test
    void aBoxAlreadyKnownIsEnrolledWithTheNextNumber() throws IOException {
        assertThat(run("organisation-creer", "--organisation", "chu-exemple", "--cles", p("org.json"))).isZero();
        Organization organization = new Organization("chu-exemple",
                KeyFiles.readPrivate(dir.resolve("org.json"), "phrase du responsable".toCharArray()));
        String code = createCode("Box réintégrée");
        UUID box = boxSubmits("Box réintégrée", code);
        SignedDocument old = organization.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), null, box, "Ancienne",
                Map.of(), now.minus(Duration.ofDays(200))), now.minus(Duration.ofDays(200)), Duration.ofDays(90), 3);
        published.put(box, old);

        assertThat(run("enroler-attente", "--cles", p("org.json"), "--codes", p("codes.json"), "--annuaire", url,
                "--confirmer")).isZero();
        assertThat(entry(box).serial()).isEqualTo(4);
        assertThat(entry(box).displayName()).isEqualTo("Box réintégrée");
    }
}
