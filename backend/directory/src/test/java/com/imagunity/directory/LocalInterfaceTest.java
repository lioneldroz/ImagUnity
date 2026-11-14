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
            if (path.equals("/directory/v1/enrollment-requests")) {
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
        assertThat(admin(Map.of(), new ByteArrayOutputStream()).run(new String[] {"interface", "--cles", p("org.json"),
                "--codes", p("codes.json"), "--annuaire", directoryUrl})).as("sans compte").isEqualTo(2);
    }
}
