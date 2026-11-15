package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** Interface du responsable, onglet « Démarrage » : création de l'organisation, vérifications, compte, fraîcheur. */
class LocalInterfaceStartupTest {

    private static final String ACCOUNT = "responsable:depot-essai";
    private static final String PHRASE = "phrase du responsable assez longue";

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-15T10:00:00Z"), ZoneOffset.UTC);
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final HttpClient http = HttpClient.newHttpClient();
    private final AtomicReference<String> served = new AtomicReference<>();
    private final AtomicInteger freshnessStatus = new AtomicInteger(404);
    private final AtomicInteger requestsStatus = new AtomicInteger(0);
    private HttpServer directory;
    private String directoryUrl;
    private LocalInterface ui;

    @BeforeEach
    void start() throws IOException {
        directory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        directory.createContext("/directory/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            int status;
            String body = "";
            if (path.endsWith("/organization")) {
                status = served.get() == null ? 503 : 200;
                body = served.get() == null ? "" : served.get();
            } else if (path.endsWith("/freshness")) {
                status = freshnessStatus.get();
                body = status == 200 ? "{}" : "";
            } else if (path.endsWith("/enrollment-requests")) {
                boolean good = ("Basic " + Base64.getEncoder().encodeToString(ACCOUNT.getBytes(StandardCharsets.UTF_8)))
                        .equals(auth);
                status = requestsStatus.get() != 0 ? requestsStatus.get() : good ? 200 : 401;
                body = status == 200 ? "[]" : "";
            } else {
                status = 200;
                body = "{\"organization\":\"x\",\"entries\":[],\"recognitions\":[]}";
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

    private LocalInterface open(Path keys, String account) throws IOException {
        LocalInterface.Accounts accounts = new LocalInterface.Accounts(account);
        ui = LocalInterface.start(keys, dir.resolve("codes.json"), new DirectoryCalls(directoryUrl, accounts::get), 90,
                0, clock, new PrintStream(out, true, StandardCharsets.UTF_8), null, accounts);
        return ui;
    }

    private HttpResponse<String> call(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ui.port() + path))
                .header("X-Jeton", ui.token());
        if (body != null) {
            request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        } else {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) {
        return Json.MAPPER.readTree(response.body());
    }

    private static String organization(String id, String phrase, String confirmation) {
        return Json.write(Map.of("organisation", id, "phrase", phrase, "confirmation", confirmation));
    }

    @Test
    void theOrganizationIsCreatedFromThePageThenCheckedAgainstTheDirectory() throws Exception {
        Path keys = dir.resolve("org").resolve("organisation.json");
        open(keys, null);
        assertThat(ui.initialized()).isFalse();
        JsonNode state = json(call("GET", "/api/etat", null));
        assertThat(state.path("initialisee").asBoolean()).isFalse();
        assertThat(state.path("compte").asString()).isEqualTo("requis");
        assertThat(state.path("dossier").asString()).endsWith("org");
        assertThat(state.has("organisation")).isFalse();
        HttpResponse<String> refused = call("GET", "/api/box", null);
        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.body()).contains("Démarrage");

        // Annuaire injoignable (pas encore installé) : l'onglet s'affiche quand même.
        JsonNode startup = json(call("GET", "/api/demarrage", null));
        assertThat(startup.path("annuaire").path("joignable").asBoolean()).isFalse();
        assertThat(startup.path("fraicheur").has("numero")).isFalse();

        assertThat(call("POST", "/api/organisation", organization("Mauvais Nom", PHRASE, PHRASE)).statusCode())
                .isEqualTo(422);
        assertThat(call("POST", "/api/organisation", organization("chu-exemple", PHRASE, PHRASE + "x")).body())
                .contains("diffèrent");
        assertThat(call("POST", "/api/organisation", organization("chu-exemple", "courte", "courte")).body())
                .contains("trop courte");
        HttpResponse<String> created = call("POST", "/api/organisation", organization("chu-exemple", PHRASE, PHRASE));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
        String fingerprint = json(created).path("empreinte").asString();
        assertThat(Files.exists(keys)).isTrue();
        assertThat(KeyFiles.isProtected(keys)).isTrue();
        KeyFiles.OrganizationPublicKey pub = KeyFiles.readOrganizationPublic(LocalInterface.publicFile(keys));
        assertThat(pub.organization()).isEqualTo("chu-exemple");
        assertThat(pub.key().shortCode()).isEqualTo(fingerprint);
        assertThat(KeyFiles.readPrivate(keys, PHRASE.toCharArray()).publicKey().fingerprint())
                .isEqualTo(pub.key().fingerprint());
        assertThat(json(created).path("etat").path("initialisee").asBoolean()).isTrue();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("créée depuis l'interface").doesNotContain(PHRASE);
        assertThat(call("POST", "/api/organisation", organization("autre", PHRASE, PHRASE)).statusCode())
                .as("une seule organisation").isEqualTo(409);
        assertThat(json(call("GET", "/api/cle-publique", null)).path("contenu").asString())
                .isEqualTo(Files.readString(LocalInterface.publicFile(keys)));

        // L'annuaire publie une autre clé, puis la nôtre, puis sa preuve de fraîcheur.
        served.set("{\"organization\":\"chu-exemple\",\"shortCode\":\"AAAA-BBBB\"}");
        assertThat(json(call("GET", "/api/demarrage", null)).path("annuaire").path("memeCle").asBoolean()).isFalse();
        served.set("{\"organization\":\"chu-exemple\",\"shortCode\":\"" + fingerprint + "\"}");
        JsonNode same = json(call("GET", "/api/demarrage", null)).path("annuaire");
        assertThat(same.path("memeCle").asBoolean()).isTrue();
        assertThat(same.path("fraicheurPubliee").asBoolean()).isFalse();

        // Compte de dépôt saisi dans la page, vérifié auprès de l'annuaire.
        assertThat(call("GET", "/api/demandes", null).statusCode()).as("compte pas encore saisi").isEqualTo(401);
        assertThat(call("POST", "/api/compte", "{\"utilisateur\":\"\",\"motDePasse\":\"x\"}").statusCode())
                .isEqualTo(422);
        assertThat(call("POST", "/api/compte", "{\"utilisateur\":\"responsable\",\"motDePasse\":\"faux\"}")
                .statusCode()).isEqualTo(403);
        requestsStatus.set(500);
        assertThat(call("POST", "/api/compte", "{\"utilisateur\":\"responsable\",\"motDePasse\":\"depot-essai\"}")
                .statusCode()).isEqualTo(502);
        requestsStatus.set(0);
        HttpResponse<String> logged = call("POST", "/api/compte",
                "{\"utilisateur\":\"responsable\",\"motDePasse\":\"depot-essai\"}");
        assertThat(logged.statusCode()).isEqualTo(200);
        assertThat(json(logged).path("etat").path("compte").asString()).isEqualTo("saisi");
        assertThat(call("GET", "/api/demandes", null).statusCode()).isEqualTo(200);

        // Clé de fraîcheur déléguée depuis la page, renouvelée avec le numéro suivant.
        assertThat(call("POST", "/api/fraicheur", "{\"jours\":120}").statusCode()).isEqualTo(422);
        assertThat(call("POST", "/api/fraicheur", "{\"jours\":30}").body()).contains("Phrase de passe");
        HttpResponse<String> delegated = call("POST", "/api/fraicheur", Json.write(Map.of("jours", 30, "phrase",
                PHRASE)));
        assertThat(delegated.statusCode()).as(delegated.body()).isEqualTo(200);
        Path delegation = keys.resolveSibling("delegation.json");
        Path freshKey = keys.resolveSibling("fraicheur.json");
        assertThat(Files.exists(freshKey)).isTrue();
        Freshness check = new Freshness(SignedDocument.fromJson(Files.readString(delegation)),
                FreshnessProof.sign(KeyFiles.readPrivate(freshKey), "chu-exemple", clock.instant(), Map.of(), Map.of()));
        assertThat(check.verify("chu-exemple", pub.key(), clock.instant())).isNotNull();
        freshnessStatus.set(200);
        JsonNode after = json(call("GET", "/api/demarrage", null));
        assertThat(after.path("fraicheur").path("numero").asLong()).isEqualTo(1);
        assertThat(after.path("fraicheur").path("expireBientot").asBoolean()).isFalse();
        assertThat(after.path("annuaire").path("fraicheurPubliee").asBoolean()).isTrue();
        call("POST", "/api/fraicheur", Json.write(Map.of("jours", 3, "phrase", PHRASE)));
        JsonNode renewed = json(call("GET", "/api/demarrage", null)).path("fraicheur");
        assertThat(renewed.path("numero").asLong()).isEqualTo(2);
        assertThat(renewed.path("expireBientot").asBoolean()).isTrue();
        Files.writeString(delegation, "illisible");
        assertThat(json(call("GET", "/api/demarrage", null)).path("fraicheur").path("erreur").asString())
                .contains("illisible");
    }

    @Test
    void anExistingOrganizationIsReopenedAndAnIncompleteOneRefused() throws Exception {
        Path keys = dir.resolve("organisation.json");
        HybridKeyPair pair = HybridKeyPair.generate();
        KeyFiles.writePrivate(keys, pair, PHRASE.toCharArray());
        assertThatThrownBy(() -> open(keys, null)).hasMessageContaining("incomplète")
                .hasMessageContaining("pub.json");
        KeyFiles.writeOrganizationPublic(LocalInterface.publicFile(keys), "chu-exemple", pair.publicKey());
        open(keys, ACCOUNT);
        assertThat(ui.initialized()).isTrue();
        JsonNode state = json(call("GET", "/api/etat", null));
        assertThat(state.path("organisation").asString()).isEqualTo("chu-exemple");
        assertThat(state.path("compte").asString()).isEqualTo("fourni");
        served.set("pas du json");
        assertThat(json(call("GET", "/api/demarrage", null)).path("annuaire").path("joignable").asBoolean())
                .as("réponse illisible").isFalse();
        ui.close();
        ui = null;

        Path orphan = dir.resolve("seule.json");
        KeyFiles.writeOrganizationPublic(LocalInterface.publicFile(orphan), "chu-exemple", pair.publicKey());
        assertThatThrownBy(() -> open(orphan, null)).hasMessageContaining("seule.json manquant");
        assertThat(LocalInterface.Accounts.external().state()).isEqualTo("fourni");
        assertThat(new LocalInterface.Accounts("sans-deux-points").state()).isEqualTo("requis");
    }
}
