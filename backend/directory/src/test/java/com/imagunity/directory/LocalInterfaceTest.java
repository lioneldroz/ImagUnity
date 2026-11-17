package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** Interface locale du responsable : sécurité du serveur local, validation et refus des demandes (annuaire simulé). */
class LocalInterfaceTest {

    private static final String ACCOUNT = "responsable:depot-essai";
    private static final String PHRASE = "phrase du responsable";

    @TempDir
    Path dir;

    /** Horloge réglable : l'oubli de la clé gardée se vérifie sans attendre. */
    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-14T10:00:00Z");

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
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final HttpClient http = HttpClient.newHttpClient();

    private HttpServer directory;
    private final List<String> pending = new CopyOnWriteArrayList<>();
    private final Map<UUID, SignedDocument> published = new ConcurrentHashMap<>();
    private final List<String> refused = new CopyOnWriteArrayList<>();
    private final AtomicInteger listStatus = new AtomicInteger(200);
    private final AtomicInteger publishStatus = new AtomicInteger(201);
    private final AtomicInteger deleteStatus = new AtomicInteger(204);
    private final AtomicInteger recognitionsStatus = new AtomicInteger(200);
    private final Map<String, SignedDocument> recognitions = new ConcurrentHashMap<>();
    private String directoryUrl;
    private LocalInterface ui;

    @BeforeEach
    void start() throws IOException {
        directory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        directory.createContext("/directory/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            int status;
            String body = "";
            if (path.equals("/directory/v1/recognitions") && method.equals("POST")) {
                SignedDocument document = SignedDocument.fromJson(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                status = publishStatus.get();
                if (status == 201) {
                    recognitions.put(Json.read(document.payload(), Recognition.class, "r").partner().fingerprint(),
                            document);
                }
            } else if (path.equals("/directory/v1/recognitions")) {
                status = recognitionsStatus.get();
                body = "{\"organization\":\"chu-exemple\",\"recognitions\":[" + String.join(",",
                        recognitions.values().stream().map(SignedDocument::toJson).toList()) + "]}";
            } else if (path.equals("/directory/v1/enrollment-requests")) {
                status = listStatus.get();
                body = "[" + String.join(",", pending) + "]";
            } else if (path.startsWith("/directory/v1/enrollment-requests/") && method.equals("DELETE")) {
                status = deleteStatus.get();
                String box = path.substring(path.lastIndexOf('/') + 1);
                if (status == 204) {
                    refused.add(box);
                    pending.removeIf(item -> item.contains(box));
                }
            } else if (path.equals("/directory/v1/entries") && method.equals("POST")) {
                SignedDocument entry = SignedDocument.fromJson(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                status = publishStatus.get();
                if (status == 201) {
                    UUID box = Json.read(entry.payload(), BoxEntry.class, "entrée").boxId();
                    published.put(box, entry);
                    pending.removeIf(item -> item.contains(box.toString()));
                }
            } else if (path.equals("/directory/v1/entries")) {
                status = listStatus.get();
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
        directory.start();
        directoryUrl = "http://127.0.0.1:" + directory.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (ui != null) {
            ui.close();
        }
        directory.stop(0);
    }

    private DirectoryAdmin admin(Map<String, String> environment, ByteArrayOutputStream output) {
        return new DirectoryAdmin(new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(output, true, StandardCharsets.UTF_8), clock, environment, label -> null);
    }

    private String p(String name) {
        return dir.resolve(name).toString();
    }

    /** Clé de l'organisation (protégée sauf {@code clear}) et un code d'enrôlement ; rend le code. */
    private String prepare(boolean clear, String codeName, String... groups) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DirectoryAdmin admin = admin(Map.of(DirectoryAdmin.ORGANIZATION_PASSPHRASE, PHRASE), output);
        assertThat(java.nio.file.Files.exists(dir.resolve("org.json")) ? 0 : clear ? admin.run(new String[] {"organisation-creer", "--organisation", "chu-exemple", "--cles",
                p("org.json"), "--sans-phrase"}) : admin.run(new String[] {"organisation-creer", "--organisation",
                "chu-exemple", "--cles", p("org.json")})).isZero();
        java.util.List<String> args = new java.util.ArrayList<>(List.of("code-creer", "--codes", p("codes.json"),
                "--nom", codeName));
        if (groups.length > 0) {
            args.addAll(List.of("--groupes", String.join(",", groups)));
        }
        assertThat(admin.run(args.toArray(String[]::new))).isZero();
        String line = output.toString(StandardCharsets.UTF_8).lines()
                .filter(l -> l.contains("IMAGUNITY_DIRECTORY_ENROLLMENT_CODE=")).findFirst().orElseThrow();
        return line.substring(line.indexOf('=') + 1);
    }

    private void open(char[] environmentPassphrase) throws IOException {
        ui = LocalInterface.start(Path.of(p("org.json")), Path.of(p("codes.json")),
                new DirectoryCalls(directoryUrl, () -> ACCOUNT), 90, 0, clock,
                new PrintStream(out, true, StandardCharsets.UTF_8), environmentPassphrase);
    }

    /** Une box qui dépose sa demande ; rend son identifiant. */
    private UUID boxSubmits(String proposedName, String code) {
        UUID box = UUID.randomUUID();
        SignedDocument request = EnrollmentRequest.create(HybridKeyPair.generate(),
                HybridKemKeyPair.generate().publicKey(), box, proposedName, Map.of(), clock.now);
        pending.add("{\"boxId\":\"" + box + "\",\"receivedAt\":\"" + clock.now + "\",\"submission\":"
                + EnrollmentSubmission.of(request, code).toJson() + "}");
        return box;
    }

    /** Demande d'une box donnée, avec des clés données. */
    private void boxSubmits(UUID box, HybridKeyPair keys, String code) {
        SignedDocument request = EnrollmentRequest.create(keys, HybridKemKeyPair.generate().publicKey(), box, "Box",
                Map.of(), clock.now);
        pending.add("{\"boxId\":\"" + box + "\",\"receivedAt\":\"" + clock.now + "\",\"submission\":"
                + EnrollmentSubmission.of(request, code).toJson() + "}");
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ui.port() + path))
                .header("X-Jeton", ui.token());
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return Json.MAPPER.readTree(response.body());
    }

    private JsonNode requests() throws Exception {
        HttpResponse<String> response = call("GET", "/api/demandes", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response);
    }

    private JsonNode request(UUID box) throws Exception {
        return requests().valueStream().filter(d -> d.get("boxId").asString().equals(box.toString())).findFirst()
                .orElseThrow();
    }

    private static String validation(UUID box, String fingerprint, String phrase, boolean keep) {
        return "{\"boxId\":\"" + box + "\",\"empreinte\":\"" + fingerprint + "\",\"phrase\":\"" + phrase
                + "\",\"garder\":" + keep + "}";
    }

    private BoxEntry entry(UUID box) {
        return Json.read(published.get(box).payload(), BoxEntry.class, "entrée");
    }

    @Test
    void theLocalServerOnlyAnswersItsOwnPageWithTheToken() throws Exception {
        prepare(false, "Box d'essai");
        open(null);
        assertThat(ui.url().toString()).startsWith("http://127.0.0.1:" + ui.port() + "/#jeton=" + ui.token());

        HttpResponse<String> page = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ui.port() + "/"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("interface.js");
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(v -> assertThat(v)
                .contains("default-src 'self'").contains("frame-ancestors 'none'"));
        assertThat(page.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(page.headers().firstValue("Cache-Control")).hasValue("no-store");
        assertThat(call("GET", "/interface.js", null).headers().firstValue("Content-Type"))
                .hasValueSatisfying(v -> assertThat(v).startsWith("text/javascript"));
        assertThat(call("GET", "/interface.css", null).body()).contains("--accent");
        assertThat(call("GET", "/ailleurs", null).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/", "{}").statusCode()).isEqualTo(404);

        // Sans jeton, ou avec un autre : rien.
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ui.port()
                + "/api/demandes")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ui.port() + "/api/etat"))
                .header("X-Jeton", "faux").build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);

        // Page d'un autre site, résolue vers 127.0.0.1 (rebinding DNS) : l'en-tête Host la trahit.
        try (Socket socket = new Socket("127.0.0.1", ui.port())) {
            socket.getOutputStream().write(("GET /api/etat HTTP/1.1\r\nHost: piege.exemple:" + ui.port()
                    + "\r\nX-Jeton: " + ui.token() + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            assertThat(new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
                    .startsWith("HTTP/1.1 403");
        }

        assertThat(call("POST", "/api/valider", null).statusCode()).as("sans corps JSON").isEqualTo(415);
        assertThat(call("POST", "/api/valider", "x".repeat(LocalInterface.MAX_BODY + 1)).statusCode()).isEqualTo(413);
        assertThat(call("POST", "/api/valider", "{pas du json").statusCode()).isEqualTo(400);
        assertThat(call("GET", "/api/inconnu", null).statusCode()).isEqualTo(404);
        assertThat(call("POST", "/api/valider", "{\"boxId\":\"x\"}").statusCode()).isEqualTo(400);

        JsonNode state = json(call("GET", "/api/etat", null));
        assertThat(state.get("organisation").asString()).isEqualTo("chu-exemple");
        assertThat(state.get("annuaire").asString()).isEqualTo(directoryUrl);
        assertThat(state.get("phrase").asString()).isEqualTo("requise");
    }

    @Test
    void aRequestWithACodeIsSignedHereAndTheKeyCanBeKeptForTheSession() throws Exception {
        String code = prepare(false, "CHU Exemple — imagerie Nord", "imagerie-nord");
        open(null);
        UUID box = boxSubmits("nom proposé", code);
        UUID second = boxSubmits("Box suivante", prepare(false, "Box suivante"));

        JsonNode d = request(box);
        assertThat(d.get("statut").asString()).isEqualTo("code-verifie");
        assertThat(d.get("nom").asString()).isEqualTo("CHU Exemple — imagerie Nord");
        assertThat(d.get("nomPropose").asString()).isEqualTo("nom proposé");
        assertThat(d.get("groupes").get(0).asString()).isEqualTo("imagerie-nord");
        assertThat(d.get("recueLe").asString()).isEqualTo(clock.now.toString());
        String fingerprint = d.get("empreinte").asString();

        HttpResponse<String> missing = call("POST", "/api/valider", validation(box, fingerprint, "", false));
        assertThat(missing.statusCode()).isEqualTo(422);
        assertThat(json(missing).get("erreur").asString()).contains("Phrase de passe");
        HttpResponse<String> wrong = call("POST", "/api/valider", validation(box, fingerprint, "mauvaise phrase", true));
        assertThat(wrong.statusCode()).isEqualTo(403);
        assertThat(json(wrong).get("erreur").asString()).contains("Phrase de passe incorrecte");
        assertThat(call("POST", "/api/valider", validation(box, "AUTRE-EMPREINTE", PHRASE, true)).statusCode())
                .as("demande changée depuis l'affichage").isEqualTo(409);
        assertThat(published).isEmpty();

        HttpResponse<String> done = call("POST", "/api/valider", validation(box, fingerprint, PHRASE, true));
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        assertThat(json(done).get("message").asString()).contains("CHU Exemple — imagerie Nord").contains("n° 1");
        assertThat(json(done).get("phrase").asString()).isEqualTo("gardee");
        BoxEntry entry = entry(box);
        assertThat(entry.displayName()).isEqualTo("CHU Exemple — imagerie Nord");
        assertThat(entry.groups()).containsExactly("imagerie-nord");
        assertThat(entry.validUntil()).isEqualTo(clock.now.plus(Duration.ofDays(90)));
        assertThat(EnrollmentCodes.read(Path.of(p("codes.json"))).byId(EnrollmentCode.id(code)).orElseThrow().boxId())
                .isEqualTo(box);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("enrôlée et publiée");
        assertThat(call("POST", "/api/valider", validation(box, fingerprint, PHRASE, true)).statusCode())
                .as("déjà traitée").isEqualTo(404);

        // Clé gardée : plus de phrase à saisir, jusqu'à l'oubli demandé ou après 15 minutes sans usage.
        clock.now = clock.now.plus(Duration.ofMinutes(10));
        assertThat(json(call("GET", "/api/etat", null)).get("phrase").asString()).isEqualTo("gardee");
        assertThat(json(call("POST", "/api/oublier", "{}")).get("phrase").asString()).isEqualTo("requise");
        String secondPrint = request(second).get("empreinte").asString();
        assertThat(call("POST", "/api/valider", validation(second, secondPrint, "", false)).statusCode())
                .isEqualTo(422);
        assertThat(call("POST", "/api/valider", validation(second, secondPrint, PHRASE, true)).statusCode())
                .isEqualTo(200);
        clock.now = clock.now.plus(LocalInterface.KEEP);
        assertThat(json(call("GET", "/api/etat", null)).get("phrase").asString()).isEqualTo("requise");
    }

    @Test
    void aUsedCodeDoesNotReenrollABoxWithOtherKeysOrARevokedBox() throws Exception {
        String code = prepare(false, "Box Nord");
        open(null);
        UUID box = UUID.randomUUID();
        HybridKeyPair keys = HybridKeyPair.generate();
        boxSubmits(box, keys, code);
        String fingerprint = request(box).get("empreinte").asString();
        assertThat(call("POST", "/api/valider", validation(box, fingerprint, PHRASE, false)).statusCode())
                .isEqualTo(200);

        // Même box, autres clés : code utilisé, signature refusée.
        HybridKeyPair other = HybridKeyPair.generate();
        boxSubmits(box, other, code);
        JsonNode reused = request(box);
        assertThat(reused.get("statut").asString()).isEqualTo("identifiant-pris");
        assertThat(reused.get("detail").asString()).contains("avec d'autres clés");
        assertThat(call("POST", "/api/valider", validation(box, reused.get("empreinte").asString(), PHRASE, false))
                .statusCode()).isEqualTo(409);
        assertThat(entry(box).serial()).isEqualTo(1);

        // Box révoquée, mêmes clés : le code ne la réintègre pas.
        Organization organization = new Organization("chu-exemple",
                KeyFiles.readPrivate(dir.resolve("org.json"), PHRASE.toCharArray()));
        published.put(box, organization.revoke(published.get(box), clock.now, "box volée"));
        pending.clear();
        boxSubmits(box, keys, code);
        JsonNode revoked = request(box);
        assertThat(revoked.get("statut").asString()).isEqualTo("box-revoquee");
        assertThat(revoked.get("detail").asString()).contains("révoquée");
        assertThat(call("POST", "/api/valider", validation(box, fingerprint, PHRASE, false)).statusCode())
                .isEqualTo(409);
        assertThat(entry(box).revoked()).isTrue();
    }

    @Test
    void aRevokedBoxIsNotReenrolledWithoutCodeEither() throws Exception {
        prepare(false, "inutilisé");
        open(null);
        Organization organization = new Organization("chu-exemple",
                KeyFiles.readPrivate(dir.resolve("org.json"), PHRASE.toCharArray()));
        UUID box = UUID.randomUUID();
        HybridKeyPair keys = HybridKeyPair.generate();
        published.put(box, organization.revoke(organization.enroll(EnrollmentRequest.create(keys,
                HybridKemKeyPair.generate().publicKey(), box, "Box", Map.of(), clock.now), clock.now,
                Duration.ofDays(90), 1), clock.now, "box volée"));

        boxSubmits(box, keys, null);
        JsonNode d = request(box);
        assertThat(d.get("statut").asString()).isEqualTo("box-revoquee");
        assertThat(call("POST", "/api/valider", "{\"boxId\":\"" + box + "\",\"empreinte\":\"" + d.get("empreinte")
                .asString() + "\",\"phrase\":\"" + PHRASE + "\",\"empreinteVerifiee\":true}").statusCode())
                .as("même après comparaison de l'empreinte").isEqualTo(409);
        assertThat(entry(box).revoked()).isTrue();
    }

    @Test
    void aRequestWithoutCodeNeedsTheFingerprintCheck() throws Exception {
        prepare(true, "Inutilisé");
        open(null);
        UUID box = boxSubmits("Clinique Exemple", null);
        JsonNode d = request(box);
        assertThat(d.get("statut").asString()).isEqualTo("sans-code");
        assertThat(d.has("nom")).isFalse();
        String fingerprint = d.get("empreinte").asString();
        assertThat(json(call("GET", "/api/etat", null)).get("phrase").asString()).isEqualTo("inutile");

        String base = "{\"boxId\":\"" + box + "\",\"empreinte\":\"" + fingerprint + "\"";
        HttpResponse<String> unchecked = call("POST", "/api/valider", base + "}");
        assertThat(unchecked.statusCode()).isEqualTo(422);
        assertThat(json(unchecked).get("erreur").asString()).contains("comparer d'abord l'empreinte");
        assertThat(call("POST", "/api/valider", base + ",\"empreinteVerifiee\":true,\"nom\":\"" + "x".repeat(257)
                + "\"}").statusCode()).isEqualTo(422);
        HttpResponse<String> done = call("POST", "/api/valider", base + ",\"empreinteVerifiee\":true,"
                + "\"nom\":\" Clinique Exemple — scanner \",\"groupes\":[\"urgences\",\" \"]}");
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        assertThat(entry(box).displayName()).isEqualTo("Clinique Exemple — scanner");
        assertThat(entry(box).groups()).containsExactly("urgences");

        // Nom laissé vide : celui proposé par la box.
        UUID other = boxSubmits("Cabinet Exemple", null);
        assertThat(call("POST", "/api/valider", "{\"boxId\":\"" + other + "\",\"empreinte\":\""
                + request(other).get("empreinte").asString() + "\",\"empreinteVerifiee\":true}").statusCode())
                .isEqualTo(200);
        assertThat(entry(other).displayName()).isEqualTo("Cabinet Exemple");
        assertThat(entry(other).groups()).isEmpty();
    }

    @Test
    void unusableRequestsAreShownButNotSignedAndCanBeRefused() throws Exception {
        String code = prepare(false, "Box d'essai");
        open(PHRASE.toCharArray());
        assertThat(json(call("GET", "/api/etat", null)).get("phrase").asString()).isEqualTo("environnement");
        UUID box = boxSubmits("Box d'essai", code);
        pending.add("{\"boxId\":\"" + UUID.randomUUID() + "\",\"submission\":{\"illisible\":true}}");
        pending.add("{\"submission\":{\"illisible\":true}}");
        clock.now = clock.now.plus(Duration.ofDays(31));

        JsonNode list = requests();
        assertThat(list.size()).as("l'élément sans box est écarté").isEqualTo(2);
        assertThat(request(box).get("statut").asString()).isEqualTo("code-expire");
        assertThat(list.valueStream().filter(d -> d.get("statut").asString().equals("illisible")).count()).isOne();
        HttpResponse<String> expired = call("POST", "/api/valider", validation(box,
                request(box).get("empreinte").asString(), "", false));
        assertThat(expired.statusCode()).isEqualTo(409);
        assertThat(json(expired).get("erreur").asString()).contains("code expiré");

        HttpResponse<String> refusal = call("POST", "/api/refuser", "{\"boxId\":\"" + box + "\"}");
        assertThat(refusal.statusCode()).as(refusal.body()).isEqualTo(200);
        assertThat(refused).containsExactly(box.toString());
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("refusée");
        deleteStatus.set(404);
        assertThat(call("POST", "/api/refuser", "{\"boxId\":\"" + box + "\"}").statusCode()).isEqualTo(404);
        deleteStatus.set(500);
        assertThat(call("POST", "/api/refuser", "{\"boxId\":\"" + box + "\"}").statusCode()).isEqualTo(502);
    }

    @Test
    void directoryFailuresAreReported() throws Exception {
        String code = prepare(false, "Box d'essai");
        open(PHRASE.toCharArray());
        UUID box = boxSubmits("Box d'essai", code);
        String fingerprint = request(box).get("empreinte").asString();

        publishStatus.set(409);
        HttpResponse<String> refusedPublication = call("POST", "/api/valider", validation(box, fingerprint, "", false));
        assertThat(refusedPublication.statusCode()).isEqualTo(502);
        assertThat(json(refusedPublication).get("erreur").asString()).contains("Publication refusée");
        assertThat(EnrollmentCodes.read(Path.of(p("codes.json"))).byId(EnrollmentCode.id(code)).orElseThrow().usedAt())
                .as("code toujours libre").isNull();

        listStatus.set(401);
        assertThat(json(call("GET", "/api/demandes", null)).get("erreur").asString())
                .contains("compte du responsable incorrect");
        listStatus.set(500);
        assertThat(call("GET", "/api/demandes", null).statusCode()).isEqualTo(502);

        directory.stop(0);
        HttpResponse<String> down = call("GET", "/api/demandes", null);
        assertThat(down.statusCode()).isEqualTo(502);
        assertThat(json(down).get("erreur").asString()).contains("Annuaire injoignable");
    }

    /** Box enrôlée par l'interface (code de ce responsable) ; rend son identifiant. */
    private UUID enrolled(String name) throws Exception {
        UUID box = boxSubmits("nom local", prepare(false, name));
        HttpResponse<String> done = call("POST", "/api/valider", validation(box,
                request(box).get("empreinte").asString(), PHRASE, true));
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        return box;
    }

    private JsonNode box(UUID box) throws Exception {
        HttpResponse<String> response = call("GET", "/api/box", null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json(response).valueStream().filter(b -> b.get("boxId").asString().equals(box.toString())).findFirst()
                .orElseThrow();
    }

    @Test
    void entriesAreRenewedAndRevokedFromTheInterface() throws Exception {
        prepare(false, "Préparation");
        open(null);
        UUID nord = enrolled("CHU Exemple — imagerie Nord");
        UUID sud = enrolled("CHU Exemple — imagerie Sud");
        // Entrée signée par une autre organisation : ni montrée, ni réémise.
        Organization other = new Organization("chu-exemple", HybridKeyPair.generate());
        UUID foreign = UUID.randomUUID();
        published.put(foreign, other.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), null, foreign,
                "Intruse", Map.of(), clock.now), clock.now, Duration.ofDays(90), 1));
        assertThat(json(call("GET", "/api/box", null)).size()).isEqualTo(2);
        assertThat(json(call("GET", "/api/etat", null)).get("renouvelerAvantJours").asLong()).isEqualTo(15);

        JsonNode b = box(nord);
        assertThat(b.get("statut").asString()).isEqualTo("ACTIVE");
        assertThat(b.get("aRenouveler").asBoolean()).isFalse();
        assertThat(b.get("serie").asLong()).isEqualTo(1);
        assertThat(b.get("nom").asString()).isEqualTo("CHU Exemple — imagerie Nord");

        // 80 jours plus tard : à renouveler ; 100 jours : expirée, renouvelable aussi.
        clock.now = clock.now.plus(Duration.ofDays(80));
        assertThat(box(nord).get("aRenouveler").asBoolean()).isTrue();
        clock.now = clock.now.plus(Duration.ofDays(20));
        assertThat(box(sud).get("statut").asString()).isEqualTo("EXPIRED");
        assertThat(box(sud).get("aRenouveler").asBoolean()).isTrue();

        assertThat(call("POST", "/api/renouveler", "{\"boxIds\":[]}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/renouveler", "{\"boxIds\":[\"x\"]}").statusCode()).isEqualTo(400);
        assertThat(call("POST", "/api/renouveler", "{\"boxIds\":[\"" + foreign + "\"]}").statusCode())
                .as("entrée d'une autre organisation").isEqualTo(404);
        assertThat(call("POST", "/api/renouveler", "{\"boxIds\":[\"" + nord + "\"]}").statusCode())
                .as("clé oubliée après 15 minutes").isEqualTo(422);
        HttpResponse<String> renewed = call("POST", "/api/renouveler", "{\"boxIds\":[\"" + nord + "\",\"" + sud
                + "\"],\"phrase\":\"" + PHRASE + "\",\"garder\":true}");
        assertThat(renewed.statusCode()).as(renewed.body()).isEqualTo(200);
        assertThat(json(renewed).get("message").asString()).startsWith("2 entrée(s) renouvelée(s)");
        assertThat(box(sud).get("statut").asString()).isEqualTo("ACTIVE");
        assertThat(box(sud).get("serie").asLong()).isEqualTo(2);
        assertThat(entry(sud).validUntil()).isEqualTo(clock.now.plus(Duration.ofDays(90)));
        assertThat(entry(sud).groups()).isEmpty();

        publishStatus.set(409);
        assertThat(call("POST", "/api/renouveler", "{\"boxIds\":[\"" + nord + "\"]}").statusCode()).isEqualTo(502);
        assertThat(call("POST", "/api/revoquer", "{\"boxId\":\"" + nord + "\",\"motif\":\"essai\","
                + "\"confirmation\":\"CHU Exemple — imagerie Nord\"}").statusCode()).isEqualTo(502);
        publishStatus.set(201);

        String revoke = "{\"boxId\":\"" + nord + "\",\"motif\":\"box remplacée\",\"confirmation\":\"";
        assertThat(call("POST", "/api/revoquer", "{\"boxId\":\"" + nord + "\"}").statusCode()).as("sans motif")
                .isEqualTo(422);
        HttpResponse<String> unconfirmed = call("POST", "/api/revoquer", revoke + "CHU Exemple\"}");
        assertThat(unconfirmed.statusCode()).isEqualTo(422);
        assertThat(json(unconfirmed).get("erreur").asString()).contains("Recopier exactement le nom");
        assertThat(call("POST", "/api/revoquer", "{\"boxId\":\"" + foreign + "\",\"motif\":\"x\"}")
                .statusCode()).isEqualTo(404);
        HttpResponse<String> revoked = call("POST", "/api/revoquer", revoke + "CHU Exemple — imagerie Nord\"}");
        assertThat(revoked.statusCode()).as(revoked.body()).isEqualTo(200);
        JsonNode r = box(nord);
        assertThat(r.get("statut").asString()).isEqualTo("REVOKED");
        assertThat(r.get("motif").asString()).isEqualTo("box remplacée");
        assertThat(r.get("aRenouveler").asBoolean()).isFalse();
        assertThat(entry(nord).serial()).isEqualTo(3);
        assertThat(call("POST", "/api/revoquer", revoke + "CHU Exemple — imagerie Nord\"}").statusCode())
                .isEqualTo(409);
        assertThat(call("POST", "/api/renouveler", "{\"boxIds\":[\"" + nord + "\"]}").statusCode()).isEqualTo(409);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("révoquée").contains("renouvelée");

        listStatus.set(500);
        assertThat(call("GET", "/api/box", null).statusCode()).isEqualTo(502);
    }

    @Test
    void enrollmentCodesAreDrawnListedAndWithdrawn() throws Exception {
        prepare(false, "Box d'essai");
        open(null);
        HttpResponse<String> created = call("POST", "/api/codes", "{\"nom\":\" Clinique Exemple \","
                + "\"groupes\":[\"urgences\",\" \"],\"validiteJours\":10}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        JsonNode c = json(created);
        String code = c.get("code").asString();
        assertThat(code).matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){4}");
        assertThat(c.get("ligne").asString()).isEqualTo("IMAGUNITY_DIRECTORY_ENROLLMENT_CODE=" + code);
        assertThat(c.get("nom").asString()).isEqualTo("Clinique Exemple");
        assertThat(c.get("expireLe").asString()).isEqualTo(clock.now.plus(Duration.ofDays(10)).toString());
        assertThat(call("POST", "/api/codes", "{\"nom\":\" \"}").statusCode()).isEqualTo(422);
        assertThat(call("POST", "/api/codes", "{\"nom\":\"x\",\"validiteJours\":0}").statusCode()).isEqualTo(422);
        assertThat(call("POST", "/api/codes", "{\"nom\":\"x\",\"groupes\":[\"Pas Valide\"]}").statusCode())
                .isEqualTo(422);

        JsonNode codes = json(call("GET", "/api/codes", null));
        assertThat(codes.size()).isEqualTo(2);
        JsonNode first = codes.get(0);
        assertThat(first.get("nom").asString()).as("le plus récent d'abord").isEqualTo("Clinique Exemple");
        assertThat(first.get("statut").asString()).isEqualTo("libre");
        assertThat(first.get("code").asString()).isEqualTo(code);
        assertThat(first.get("groupes").get(0).asString()).isEqualTo("urgences");

        // Code utilisé par une box : gardé pour mémoire, sans le code lui-même.
        UUID box = boxSubmits("Box", code);
        assertThat(call("POST", "/api/valider", validation(box, request(box).get("empreinte").asString(), PHRASE,
                false)).statusCode()).isEqualTo(200);
        JsonNode used = json(call("GET", "/api/codes", null)).get(0);
        assertThat(used.get("statut").asString()).isEqualTo("utilise");
        assertThat(used.get("boxId").asString()).isEqualTo(box.toString());
        assertThat(used.has("code")).isFalse();
        assertThat(call("POST", "/api/codes/retirer", "{\"id\":\"" + used.get("id").asString() + "\"}")
                .statusCode()).isEqualTo(409);

        // Le premier code expire au bout de 30 jours ; il peut être retiré.
        clock.now = clock.now.plus(Duration.ofDays(31));
        JsonNode expired = json(call("GET", "/api/codes", null)).get(1);
        assertThat(expired.get("statut").asString()).isEqualTo("expire");
        assertThat(call("POST", "/api/codes/retirer", "{\"id\":\"" + expired.get("id").asString() + "\"}")
                .statusCode()).isEqualTo(200);
        assertThat(json(call("GET", "/api/codes", null)).size()).isEqualTo(1);
        assertThat(call("POST", "/api/codes/retirer", "{\"id\":\"inconnu\"}").statusCode()).isEqualTo(404);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("retiré");
    }

    @Test
    void partnerOrganizationsAreRecognizedRenewedAndRevoked() throws Exception {
        prepare(false, "Box d'essai");
        open(null);
        HybridKeyPair partnerKeys = HybridKeyPair.generate();
        Path partnerFile = dir.resolve("partenaire.pub.json");
        KeyFiles.writeOrganizationPublic(partnerFile, "bourgogne-fc", partnerKeys.publicKey());
        String pasted = java.nio.file.Files.readString(partnerFile);
        String fp = partnerKeys.publicKey().fingerprint();
        assertThat(json(call("GET", "/api/partenaires", null)).size()).isZero();

        JsonNode key = json(call("POST", "/api/partenaires/cle", Json.write(Map.of("cle", pasted))));
        assertThat(key.get("organisation").asString()).isEqualTo("bourgogne-fc");
        assertThat(key.get("empreinteCourte").asString()).isEqualTo(partnerKeys.publicKey().shortCode());
        assertThat(call("POST", "/api/partenaires/cle", "{\"cle\":\" \"}").statusCode()).isEqualTo(422);
        assertThat(call("POST", "/api/partenaires/cle", "{\"cle\":\"pas une clé\"}").statusCode()).isEqualTo(422);
        assertThat(json(call("POST", "/api/partenaires/cle", Json.write(Map.of("cle", java.nio.file.Files.readString(
                Path.of(p("org.json.pub.json"))))))).get("erreur").asString()).contains("cette organisation");

        Map<String, Object> request = new java.util.LinkedHashMap<>(Map.of("cle", pasted, "empreinte", fp,
                "annuaire", "https://annuaire.bourgogne-fc.example", "relais", List.of("https://relais.b.example"),
                "groupesLocaux", List.of("*"), "groupesPartenaire", List.of("neuro"), "phrase", PHRASE));
        assertThat(json(call("POST", "/api/partenaires", Json.write(request))).get("erreur").asString())
                .contains("Comparer d'abord");
        request.put("empreinteVerifiee", true);
        request.put("empreinte", "autre");
        assertThat(call("POST", "/api/partenaires", Json.write(request)).statusCode()).isEqualTo(409);
        request.put("empreinte", fp);
        request.put("relais", List.of());
        assertThat(call("POST", "/api/partenaires", Json.write(request)).statusCode()).isEqualTo(422);
        request.put("relais", List.of("https://relais.b.example", " "));
        HttpResponse<String> done = call("POST", "/api/partenaires", Json.write(request));
        assertThat(done.statusCode()).as(done.body()).isEqualTo(200);
        assertThat(json(done).get("message").asString()).contains("reconnue").contains("à son tour");
        request.put("groupesPartenaire", List.of());
        assertThat(json(call("POST", "/api/partenaires", Json.write(request))).get("message").asString())
                .as("réémission, périmètre vide").contains("périmètre vide");

        JsonNode listed = json(call("GET", "/api/partenaires", null)).get(0);
        assertThat(listed.get("organisation").asString()).isEqualTo("bourgogne-fc");
        assertThat(listed.get("statut").asString()).isEqualTo("ACTIVE");
        assertThat(listed.get("serie").asLong()).isEqualTo(2);
        assertThat(listed.get("relais").get(0).asString()).isEqualTo("https://relais.b.example");

        clock.now = clock.now.plus(Duration.ofDays(10));
        HttpResponse<String> renewed = call("POST", "/api/partenaires/renouveler", "{\"empreinte\":\"" + fp
                + "\",\"phrase\":\"" + PHRASE + "\"}");
        assertThat(renewed.statusCode()).as(renewed.body()).isEqualTo(200);
        assertThat(call("POST", "/api/partenaires/renouveler", "{\"empreinte\":\"inconnue\"}").statusCode())
                .isEqualTo(404);

        String revoke = "{\"empreinte\":\"" + fp + "\",\"phrase\":\"" + PHRASE + "\",\"motif\":\"fin\",";
        assertThat(call("POST", "/api/partenaires/revoquer", "{\"empreinte\":\"" + fp + "\"}").statusCode())
                .as("sans motif").isEqualTo(422);
        assertThat(call("POST", "/api/partenaires/revoquer", revoke + "\"confirmation\":\"bourgogne\"}")
                .statusCode()).isEqualTo(422);
        assertThat(call("POST", "/api/partenaires/revoquer", revoke + "\"confirmation\":\"bourgogne-fc\"}")
                .statusCode()).isEqualTo(200);
        JsonNode revoked = json(call("GET", "/api/partenaires", null)).get(0);
        assertThat(revoked.get("statut").asString()).isEqualTo("REVOKED");
        assertThat(revoked.get("motif").asString()).isEqualTo("fin");
        assertThat(call("POST", "/api/partenaires/revoquer", revoke + "\"confirmation\":\"bourgogne-fc\"}")
                .statusCode()).isEqualTo(409);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("reconnue").contains("révoquée");

        // Reconnaissance publiée par une autre clé : ignorée ; annuaire en panne ou antérieur à la fédération.
        recognitions.put("x", new Organization("chu-exemple", HybridKeyPair.generate()).recognize(
                new Recognition.Partner("autre", HybridKeyPair.generate().publicKey().encoded(), "https://a.example",
                        List.of("https://r.example")), Recognition.Scope.none(), clock.now, Duration.ofDays(1)));
        assertThat(json(call("GET", "/api/partenaires", null)).size()).isEqualTo(1);
        publishStatus.set(500);
        assertThat(call("POST", "/api/partenaires/renouveler", "{\"empreinte\":\"" + fp + "\",\"phrase\":\""
                + PHRASE + "\"}").statusCode()).isEqualTo(502);
        recognitionsStatus.set(500);
        assertThat(call("GET", "/api/partenaires", null).statusCode()).isEqualTo(502);
        recognitionsStatus.set(404);
        assertThat(json(call("GET", "/api/partenaires", null)).size()).isZero();
    }

    @Test
    void theToolCommandServesUntilTheResponsibleQuits() throws Exception {
        System.setProperty("java.awt.headless", "true");
        prepare(false, "Box d'essai");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        DirectoryAdmin admin = admin(Map.of(DirectoryAdmin.ORGANIZATION_PASSPHRASE, PHRASE,
                DirectoryAdmin.ACCOUNT_VARIABLE, ACCOUNT), output);
        int[] exit = {-1};
        Thread tool = Thread.ofPlatform().start(() -> exit[0] = admin.run(new String[] {"interface", "--cles",
                p("org.json"), "--codes", p("codes.json"), "--annuaire", directoryUrl, "--ouvrir"}));
        String url = null;
        for (int i = 0; i < 200 && url == null; i++) {
            url = output.toString(StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("Interface du responsable"))
                    .map(l -> l.substring(l.indexOf("http"))).findFirst().orElse(null);
            Thread.sleep(25);
        }
        assertThat(url).isNotNull();
        URI uri = URI.create(url);
        String token = uri.getFragment().substring("jeton=".length());
        HttpResponse<String> quit = http.send(HttpRequest.newBuilder(uri.resolve("/api/quitter"))
                .header("X-Jeton", token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(quit.statusCode()).isEqualTo(200);
        tool.join(10_000);
        assertThat(exit[0]).isZero();
        assertThat(output.toString(StandardCharsets.UTF_8)).contains("Navigateur non ouvert").contains("Interface arrêtée");

        assertThat(admin.run(new String[] {"interface", "--cles", p("org.json"), "--codes", p("codes.json"),
                "--annuaire", directoryUrl, "--port", "70000"})).isEqualTo(1);
        assertThat(admin.run(new String[] {"interface", "--cles", p("org.json"), "--codes", p("codes.json")}))
                .as("sans annuaire").isEqualTo(2);

        // Sans compte ni organisation : l'interface démarre quand même, le compte se saisit dans la page.
        ByteArrayOutputStream second = new ByteArrayOutputStream();
        DirectoryAdmin bare = admin(Map.of(), second);
        Thread again = Thread.ofPlatform().start(() -> exit[0] = bare.run(new String[] {"interface", "--cles",
                p("neuve/org.json"), "--codes", p("neuve/codes.json"), "--annuaire", directoryUrl}));
        String url2 = null;
        for (int i = 0; i < 200 && url2 == null; i++) {
            url2 = second.toString(StandardCharsets.UTF_8).lines().filter(l -> l.startsWith("Interface du responsable"))
                    .map(l -> l.substring(l.indexOf("http"))).findFirst().orElse(null);
            Thread.sleep(25);
        }
        assertThat(url2).isNotNull();
        URI uri2 = URI.create(url2);
        String token2 = uri2.getFragment().substring("jeton=".length());
        HttpResponse<String> state = http.send(HttpRequest.newBuilder(uri2.resolve("/api/etat")).header("X-Jeton", token2)
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(state.body()).contains("\"initialisee\":false").contains("\"compte\":\"requis\"");
        http.send(HttpRequest.newBuilder(uri2.resolve("/api/quitter")).header("X-Jeton", token2)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                HttpResponse.BodyHandlers.ofString());
        again.join(10_000);
        assertThat(exit[0]).isZero();
        assertThat(second.toString(StandardCharsets.UTF_8)).contains("propose de la créer");
    }
}
