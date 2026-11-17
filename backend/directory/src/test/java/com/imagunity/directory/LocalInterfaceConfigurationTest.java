package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

/** Interface du responsable, onglet « Configuration des box » : document en vigueur, signature et publication. */
class LocalInterfaceConfigurationTest {

    private static final String PHRASE = "phrase du responsable assez longue";
    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final HttpClient http = HttpClient.newHttpClient();
    private final HybridKeyPair orgKeys = HybridKeyPair.generate();
    private final Organization org = new Organization("chu-exemple", orgKeys);
    private final Organization partner = new Organization("bourgogne-fc", HybridKeyPair.generate());
    private final UUID nord = UUID.randomUUID();
    private final UUID sud = UUID.randomUUID();
    private final UUID revoked = UUID.randomUUID();
    private final UUID dijon = UUID.randomUUID();
    private final List<SignedDocument> entries = new CopyOnWriteArrayList<>();
    private final List<SignedDocument> configurations = new CopyOnWriteArrayList<>();
    private final List<SignedDocument> recognitions = new CopyOnWriteArrayList<>();
    private final List<SignedDocument> partnerEntries = new CopyOnWriteArrayList<>();
    private final AtomicInteger publishStatus = new AtomicInteger(201);
    private final AtomicInteger configurationsStatus = new AtomicInteger(200);
    private final AtomicInteger stateStatus = new AtomicInteger(200);
    private HttpServer directory;
    private LocalInterface ui;

    private SignedDocument entry(Organization o, UUID box, String name, List<String> groups) {
        return o.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), box, name, Map.of(), NOW), NOW,
                Duration.ofDays(90), 1, groups, name);
    }

    private static String list(String key, List<SignedDocument> documents) {
        return "{\"organization\":\"x\",\"" + key + "\":[" + String.join(",", documents.stream()
                .map(SignedDocument::toJson).toList()) + "]}";
    }

    @BeforeEach
    void start() throws IOException {
        entries.add(entry(org, nord, "Box Nord", List.of("imagerie")));
        entries.add(entry(org, sud, "Box Sud", List.of()));
        SignedDocument gone = entry(org, revoked, "Box retirée", List.of());
        entries.add(org.revoke(gone, NOW, "essai"));
        entries.add(SignedDocument.sign("{}", HybridKeyPair.generate()));
        Recognition.Partner p = new Recognition.Partner(partner.id(), partner.publicKey().encoded(),
                "https://annuaire.b.example", List.of("https://relais.b.example"));
        recognitions.add(org.recognize(p, new Recognition.Scope(List.of("*"), List.of("*")), NOW,
                Duration.ofDays(90)));
        partnerEntries.add(entry(partner, dijon, "Box Dijon", List.of()));
        partnerEntries.add(entry(org, UUID.randomUUID(), "Fausse", List.of()));
        // Document pour le groupe « imagerie », avec des règles de priorité qu'un document par box doit garder.
        configurations.add(org.signConfiguration(CentralConfiguration.draft(org.id(),
                CentralConfiguration.Target.groups(List.of("imagerie")), 4, NOW.minusSeconds(60), null, null,
                new CentralConfiguration.Priority(3, List.of()), null)));
        configurations.add(SignedDocument.sign("{}", HybridKeyPair.generate()));
        directory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        directory.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            int status = 200;
            String body;
            if (path.equals("/directory/v1/entries")) {
                body = list("entries", entries);
            } else if (path.equals("/directory/v1/configurations") && method.equals("POST")) {
                status = publishStatus.get();
                SignedDocument document = SignedDocument.fromJson(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8));
                if (status == 201) {
                    configurations.add(document);
                }
                body = status == 422 ? "{\"detail\":\"Règle invalide : mot-clé inconnu\"}" : status == 500 ? "brut"
                        : "";
            } else if (path.equals("/directory/v1/configurations")) {
                status = configurationsStatus.get();
                body = list("configurations", configurations);
            } else if (path.equals("/admin/api/state")) {
                status = stateStatus.get();
                body = "{\"boxes\":[{\"boxId\":\"" + nord + "\",\"applied\":4,\"refused\":5,\"refusal\":\"règle\"},"
                        + "{\"boxId\":\"" + sud + "\"}]}";
            } else if (path.equals("/directory/v1/recognitions")) {
                body = list("recognitions", recognitions);
            } else if (path.endsWith("/entries")) {
                body = list("entries", partnerEntries);
            } else {
                status = 404;
                body = "";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        directory.start();
        KeyFiles.writePrivate(dir.resolve("org.json"), orgKeys, PHRASE.toCharArray());
        KeyFiles.writeOrganizationPublic(LocalInterface.publicFile(dir.resolve("org.json")), org.id(),
                orgKeys.publicKey());
        LocalInterface.Accounts accounts = new LocalInterface.Accounts("responsable:depot");
        ui = LocalInterface.start(dir.resolve("org.json"), dir.resolve("codes.json"), new DirectoryCalls(
                "http://127.0.0.1:" + directory.getAddress().getPort(), accounts::get), 90, 0, clock,
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8), null, accounts);
    }

    @AfterEach
    void stop() {
        ui.close();
        directory.stop(0);
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

    private static JsonNode json(HttpResponse<String> response) {
        return Json.MAPPER.readTree(response.body());
    }

    private static String dicomBody(UUID box, String keyword, String phrase) {
        return "{\"boxId\":\"" + box + "\",\"motif\":\"PACS IRM\",\"phrase\":\"" + phrase + "\",\"dicom\":{"
                + "\"aet\":\"IMAGUNITY_NORD\",\"nodes\":[{\"name\":\"pacs-nord\",\"aet\":\"PACS_NORD\",\"host\":"
                + "\"10.2.0.15\",\"port\":104,\"source\":true,\"destination\":true}],\"outgoing\":[{\"name\":\"ct\","
                + "\"from\":[\"pacs-nord\"],\"and\":{\"" + keyword + "\":\"CT\"},\"to\":[\"" + UUID.randomUUID()
                + "\"]}],\"incoming\":[{\"name\":\"tout\",\"to\":[\"pacs-nord\"]}]}}";
    }

    @Test
    void eachBoxShowsItsConfigurationAndTheBoxesARouteCanName() throws Exception {
        JsonNode c = json(call("GET", "/api/configurations", null));
        assertThat(c.path("prochainNumero").asLong()).isEqualTo(5);
        JsonNode boxes = c.path("box");
        assertThat(boxes).hasSize(2);
        JsonNode first = boxes.get(0).path("boxId").asString().equals(nord.toString()) ? boxes.get(0) : boxes.get(1);
        JsonNode other = first == boxes.get(0) ? boxes.get(1) : boxes.get(0);
        assertThat(first.path("numero").asLong()).isEqualTo(4);
        assertThat(first.path("cible").asString()).isEqualTo("groupes imagerie");
        assertThat(first.path("priorite").asBoolean()).isTrue();
        assertThat(first.path("appliquee").asLong()).isEqualTo(4);
        assertThat(first.path("refus").asString()).isEqualTo("règle");
        assertThat(other.path("numero").asLong()).isZero();
        assertThat(other.path("cible").isNull()).isTrue();
        assertThat(c.path("destinations").findValuesAsString("nom")).containsExactlyInAnyOrder("Box Nord", "Box Sud",
                "Box Dijon");

        // Comptes rendus et annuaire du partenaire facultatifs ; configurations illisibles : refus.
        stateStatus.set(500);
        recognitions.clear();
        JsonNode reduced = json(call("GET", "/api/configurations", null));
        assertThat(reduced.path("destinations")).hasSize(2);
        configurationsStatus.set(404);
        assertThat(json(call("GET", "/api/configurations", null)).path("prochainNumero").asLong()).isEqualTo(1);
        configurationsStatus.set(500);
        assertThat(call("GET", "/api/configurations", null).statusCode()).isEqualTo(502);
    }

    @Test
    void theConfigurationIsSignedForThisBoxAloneAndKeepsTheRules() throws Exception {
        HttpResponse<String> published = call("POST", "/api/configuration", dicomBody(nord, "Modality", PHRASE));
        assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
        assertThat(json(published).path("message").asString()).contains("n° 5").contains("« Box Nord »");
        SignedDocument last = configurations.getLast();
        CentralConfiguration c = CentralConfiguration.verify(last, org.id(), orgKeys.publicKey(), clock);
        assertThat(c.target().boxes()).containsExactly(nord);
        assertThat(c.sequence()).isEqualTo(5);
        assertThat(c.comment()).isEqualTo("PACS IRM");
        assertThat(c.priority().defaultPriority()).as("règles du document en vigueur gardées").isEqualTo(3);
        assertThat(c.dicom().aet()).isEqualTo("IMAGUNITY_NORD");
        assertThat(c.dicom().incoming()).hasSize(1);

        assertThat(call("POST", "/api/configuration", dicomBody(nord, "Modality", "fausse phrase")).statusCode())
                .isEqualTo(403);
        publishStatus.set(422);
        assertThat(call("POST", "/api/configuration", dicomBody(nord, "Modalite", PHRASE)).body())
                .contains("Publication refusée par l'annuaire : Règle invalide : mot-clé inconnu");
        publishStatus.set(500);
        assertThat(call("POST", "/api/configuration", dicomBody(nord, "Modality", PHRASE)).body())
                .contains("brut");
        publishStatus.set(201);
        assertThat(call("POST", "/api/configuration", dicomBody(revoked, "Modality", PHRASE)).statusCode())
                .as("box révoquée").isEqualTo(404);
        assertThat(call("POST", "/api/configuration", "{\"boxId\":\"" + sud + "\",\"phrase\":\"" + PHRASE + "\"}")
                .body()).contains("absente");
        assertThat(call("POST", "/api/configuration", "{\"boxId\":\"" + sud + "\",\"dicom\":{\"nodes\":\"x\"}}")
                .body()).contains("illisible");
        assertThat(call("POST", "/api/configuration", "{\"boxId\":\"" + sud + "\",\"phrase\":\"" + PHRASE
                + "\",\"dicom\":{\"nodes\":[{\"name\":\"x\",\"aet\":\"X\",\"source\":false,\"destination\":false}]}}")
                .body()).as("forme refusée avant la signature").contains("ni source ni destination");
        HttpResponse<String> forSud = call("POST", "/api/configuration", "{\"boxId\":\"" + sud + "\",\"phrase\":\""
                + PHRASE + "\",\"dicom\":{\"nodes\":[{\"name\":\"ct\",\"aet\":\"CT\",\"source\":true}]}}");
        assertThat(forSud.statusCode()).as(forSud.body()).isEqualTo(200);
        CentralConfiguration sudConfiguration = CentralConfiguration.verify(configurations.getLast(), org.id(),
                orgKeys.publicKey(), clock);
        assertThat(sudConfiguration.priority()).as("aucun document en vigueur").isNull();
        assertThat(sudConfiguration.comment()).isNull();
    }
}
