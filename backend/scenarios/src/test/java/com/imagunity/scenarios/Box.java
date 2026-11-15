package com.imagunity.scenarios;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Une vraie box ImagUnity, démarrée dans la JVM des tests avec sa propre configuration : base H2 et dossiers sur
 * disque (elle peut être arrêtée puis redémarrée sans rien perdre), entrée DICOM et console sur la boucle locale,
 * transport IU-T par le relais du banc, annuaire signé du banc.
 *
 * <p>Chaque box a son PACS simulé ({@link #pacs}) : celui de son établissement, qui lui envoie les examens (AET
 * {@link #pacsAet()}) et reçoit ceux qu'elle restitue.
 */
final class Box implements AutoCloseable {

    /**
     * Configuration d'une box.
     *
     * @param name        nom de la box (et AET de son PACS : {@code PACS_<NOM>})
     * @param routes      destinations DICOM : AET appelé → partenaire destinataire
     * @param routingAet  destination DICOM routée par les champs DICOM ({@code null} : aucune)
     * @param rules       règles de routage par champ DICOM, sur {@code routingAet}
     * @param maxPartSize taille maximale d'un paquet (petite : examen découpé en plusieurs paquets)
     */
    record Spec(String name, Map<String, String> routes, String routingAet, List<Rule> rules, String maxPartSize) {

        static Spec of(String name) {
            return new Spec(name, Map.of(), null, List.of(), "900MB");
        }

        Spec route(String calledAet, String destination) {
            Map<String, String> all = new java.util.LinkedHashMap<>(routes);
            all.put(calledAet, destination);
            return new Spec(name, Map.copyOf(all), routingAet, rules, maxPartSize);
        }

        Spec routing(String aet, Rule... rules) {
            return new Spec(name, routes, aet, List.of(rules), maxPartSize);
        }

        Spec maxPartSize(String size) {
            return new Spec(name, routes, routingAet, rules, size);
        }
    }

    /** Règle de routage : champs DICOM qui doivent tous correspondre → partenaires. */
    record Rule(String name, Map<String, String> and, List<String> to) {
    }

    private final Bench bench;
    final Spec spec;
    final Path dataDir;
    final int dicomPort = FakePacs.freePort();
    final FakePacs pacs;
    private ConfigurableApplicationContext context;
    private URI url;
    private UUID boxId;

    Box(Bench bench, Spec spec) {
        this.bench = bench;
        this.spec = spec;
        this.dataDir = bench.dir.resolve("box/" + spec.name());
        this.pacs = new FakePacs(pacsAet()).start();
    }

    /** AET du PACS de l'établissement de cette box. */
    String pacsAet() {
        return "PACS_" + spec.name().toUpperCase(Locale.ROOT).replace('-', '_');
    }

    /** Démarre la box (ou la redémarre après {@link #stop()}, avec son état). */
    void start() {
        if (context != null) {
            return;
        }
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path conf = bench.dir.resolve("conf/box");
        Path yml = AppFiles.copy(AppFiles.load("com.imagunity.ImagUnityApplication"), "application.yml", conf);
        Path migrations = AppFiles.copy(AppFiles.load("com.imagunity.persistence.JdbcTransferRepository"),
                "db/migration", conf);
        List<String> args = new ArrayList<>(List.of(
                "--spring.config.location=file:" + yml,
                "--spring.flyway.locations=filesystem:" + migrations,
                "--spring.jmx.enabled=false",
                "--server.port=" + (url == null ? 0 : url.getPort()), "--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:file:" + dataDir.resolve("imagunity"),
                "--imagunity.box.name=" + spec.name(),
                // Entrée DICOM
                "--imagunity.dicom.bind-address=127.0.0.1",
                "--imagunity.dicom.port=" + dicomPort,
                "--imagunity.dicom.storage-dir=" + dataDir.resolve("incoming"),
                "--imagunity.dicom.study-idle-timeout=1s",
                "--imagunity.dicom.min-free-space=1MB",
                "--imagunity.dicom.allowed-calling-aets[0]=" + pacsAet(),
                "--imagunity.dicomweb.user=" + Bench.DICOMWEB_USER,
                "--imagunity.dicomweb.password=" + Bench.DICOMWEB_PASSWORD,
                // Envoi et restitution
                "--imagunity.orchestration.outbox-dir=" + dataDir.resolve("outbox"),
                "--imagunity.orchestration.max-part-size=" + spec.maxPartSize(),
                "--imagunity.restore.pacs-host=127.0.0.1",
                "--imagunity.restore.pacs-port=" + pacs.port,
                "--imagunity.restore.pacs-aet=" + pacsAet(),
                "--imagunity.restore.box-aet=IU_" + pacsAet().substring("PACS_".length()),
                "--imagunity.restore.inbox-dir=" + dataDir.resolve("inbox"),
                // Console
                "--imagunity.console.user=" + Bench.CONSOLE_USER,
                "--imagunity.console.password=" + Bench.CONSOLE_PASSWORD,
                "--imagunity.console.generated-password-file=" + dataDir.resolve("mot-de-passe-console.txt"),
                // Annuaire et configuration centralisée
                "--imagunity.directory.enabled=true",
                "--imagunity.directory.url=" + bench.directoryUrl,
                "--imagunity.directory.organization-key=" + bench.dir.resolve("organisation.pub.json"),
                "--imagunity.directory.organization-code=" + bench.organizationCode(),
                "--imagunity.directory.identity-file=" + dataDir.resolve("identite-box.json"),
                "--imagunity.directory.identity-passphrase=phrase-de-passe-scenario-" + spec.name(),
                "--imagunity.directory.sync-interval=1s",
                "--imagunity.central-configuration.file=" + dataDir.resolve("configuration-centrale.json"),
                // Transport IU-T
                "--imagunity.transport.iu.relay=" + bench.relayUrl,
                "--imagunity.transport.iu.state-dir=" + dataDir.resolve("iu"),
                "--imagunity.transport.iu.chunk-size=256KB",
                "--spring.lifecycle.timeout-per-shutdown-phase=5s"));
        int i = 0;
        for (Map.Entry<String, String> route : spec.routes().entrySet()) {
            args.add("--imagunity.dicom.routes[" + i + "].called-aet=" + route.getKey());
            args.add("--imagunity.dicom.routes[" + i + "].destination=" + route.getValue());
            i++;
        }
        if (spec.routingAet() != null) {
            args.add("--imagunity.dicom.routing-aet=" + spec.routingAet());
            for (int r = 0; r < spec.rules().size(); r++) {
                Rule rule = spec.rules().get(r);
                args.add("--imagunity.dicom.rules[" + r + "].name=" + rule.name());
                for (Map.Entry<String, String> field : rule.and().entrySet()) {
                    // Clé entre crochets : la casse du mot-clé DICOM est gardée telle quelle.
                    args.add("--imagunity.dicom.rules[" + r + "].and[" + field.getKey() + "]=" + field.getValue());
                }
                for (int t = 0; t < rule.to().size(); t++) {
                    args.add("--imagunity.dicom.rules[" + r + "].to[" + t + "]=" + rule.to().get(t));
                }
            }
        }
        context = new SpringApplicationBuilder(BoxApplication.class).run(args.toArray(String[]::new));
        url = Bench.url(context);
        boxId = UUID.fromString(Bench.json(get("/api/v1/directory").body()).path("boxId").asString());
    }

    /** Arrête la box (coupure de l'établissement) ; son état reste sur disque. */
    void stop() {
        if (context != null) {
            context.close();
            context = null;
        }
    }

    boolean running() {
        return context != null;
    }

    UUID boxId() {
        return boxId;
    }

    // --- API de la console ---

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(url.resolve(path))
                .header("Authorization", Bench.basic(Bench.CONSOLE_USER + ":" + Bench.CONSOLE_PASSWORD));
    }

    HttpResponse<String> get(String path) {
        return Bench.send(request(path).GET());
    }

    HttpResponse<String> post(String path, String json) {
        return Bench.send(request(path).header(Bench.CONSOLE_HEADER, "1").header("Content-Type", "application/json")
                .POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)));
    }

    private JsonNode getJson(String path) {
        HttpResponse<String> response = get(path);
        if (response.statusCode() != 200) {
            throw new IllegalStateException(path + " : " + response.statusCode() + " " + response.body());
        }
        return Bench.json(response.body());
    }

    /** Cette box vue par l'annuaire de la box courante. */
    Optional<JsonNode> directoryView(UUID other) {
        return stream(getJson("/api/v1/directory").path("boxes"))
                .filter(b -> other.toString().equals(b.path("boxId").asString())).findFirst();
    }

    /** Vrai si un partenaire de cette box est lié (poignée de main signée) à la box {@code other}. */
    boolean peerBoundTo(UUID other) {
        return stream(getJson("/api/v1/peers")).anyMatch(p -> other.toString().equals(p.path("boxId").asString()));
    }

    /** Transferts envoyés de cette étude, du plus récent au plus ancien. */
    List<JsonNode> sent(String studyUid) {
        return stream(getJson("/api/v1/transfers?limit=500"))
                .filter(t -> studyUid.equals(t.path("studyInstanceUid").asString())).toList();
    }

    /** Transfert envoyé de cette étude, s'il existe (le plus récent). */
    Optional<JsonNode> sentTransfer(String studyUid) {
        return sent(studyUid).stream().findFirst();
    }

    /** État du transfert envoyé de cette étude, ou {@code null}. */
    String sentState(String studyUid) {
        return sentTransfer(studyUid).map(t -> t.path("state").asString()).orElse(null);
    }

    /** Transfert reçu de cette étude, s'il existe. */
    Optional<JsonNode> receivedTransfer(String studyUid) {
        return stream(getJson("/api/v1/inbound-transfers?limit=500"))
                .filter(t -> studyUid.equals(t.path("studyInstanceUid").asString())).findFirst();
    }

    String receivedState(String studyUid) {
        return receivedTransfer(studyUid).map(t -> t.path("state").asString()).orElse(null);
    }

    /** Paquets d'un transfert envoyé. */
    List<JsonNode> parts(String transferId) {
        return stream(getJson("/api/v1/transfers/" + transferId).path("parts")).toList();
    }

    /** Relance un transfert en échec depuis la console. */
    HttpResponse<String> retry(String transferId) {
        return post("/api/v1/transfers/" + transferId + "/retry", null);
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false);
    }

    // --- Entrées DICOM ---

    /** Le PACS de l'établissement envoie l'étude en C-STORE à la destination {@code calledAet} de cette box. */
    List<Integer> cstore(String calledAet, Studies.Study study) throws Exception {
        return cstore(pacsAet(), calledAet, study);
    }

    /** Envoi en C-STORE depuis un émetteur d'AET {@code callingAet} (déclaré ou non). */
    List<Integer> cstore(String callingAet, String calledAet, Studies.Study study) throws Exception {
        try (PacsClient client = new PacsClient(callingAet, dicomPort)) {
            return client.store(calledAet, study.instances());
        }
    }

    /** Le PACS dépose l'étude en DICOMweb STOW-RS ({@code POST /dicomweb/<aet>/studies}). */
    HttpResponse<String> stow(String aet, Studies.Study study) {
        return stow(aet, study, Bench.DICOMWEB_USER + ":" + Bench.DICOMWEB_PASSWORD);
    }

    HttpResponse<String> stow(String aet, Studies.Study study, String account) {
        return stowRaw("/dicomweb/" + aet + "/studies", MULTIPART, multipart(study), account);
    }

    static final String MULTIPART = "multipart/related; type=\"application/dicom\"; boundary=limite-scenario";

    /** Corps STOW-RS : une partie {@code application/dicom} par instance. */
    static byte[] multipart(Studies.Study study) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (var instance : study.instances()) {
            body.writeBytes("--limite-scenario\r\nContent-Type: application/dicom\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            body.writeBytes(Studies.part10(instance));
            body.writeBytes("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.writeBytes("--limite-scenario--\r\n".getBytes(StandardCharsets.US_ASCII));
        return body.toByteArray();
    }

    /** Requête STOW-RS quelconque (chemin, type de contenu et corps choisis par le scénario). */
    HttpResponse<String> stowRaw(String path, String contentType, byte[] body, String account) {
        HttpRequest.Builder request = HttpRequest.newBuilder(url.resolve(path))
                .header("Content-Type", contentType)
                .header("Accept", "application/dicom+json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (account != null) {
            request.header("Authorization", Bench.basic(account));
        }
        return Bench.send(request);
    }

    @Override
    public void close() {
        stop();
        pacs.close();
    }
}
