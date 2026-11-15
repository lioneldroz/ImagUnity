package com.imagunity.directory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import tools.jackson.databind.JsonNode;

/**
 * Interface locale du responsable : une page servie sur 127.0.0.1 par son outil, qui liste les demandes
 * d'enrôlement en attente dans l'annuaire et les fait valider (signature sur ce poste, puis publication) ou refuser.
 *
 * <p>La clé de l'organisation et sa phrase de passe ne quittent jamais ce poste : la page ne fait que transmettre la
 * phrase à ce serveur local, qui signe. Seuls l'entrée signée et le refus partent vers l'annuaire.
 *
 * <p>Protections : écoute sur la boucle locale uniquement ; jeton tiré au démarrage, transmis dans le fragment de
 * l'adresse (jamais envoyé sur le réseau) et exigé dans l'en-tête {@code X-Jeton} de chaque appel ; en-tête
 * {@code Host} vérifié (rebinding DNS) ; page sans script tiers ni cadre ({@code Content-Security-Policy}).
 */
final class LocalInterface implements AutoCloseable {

    /** Entrées actives qui expirent dans ce délai : proposées au renouvellement. */
    static final Duration RENEW_BEFORE = Duration.ofDays(15);
    /** Durée sans usage après laquelle la clé gardée en mémoire est oubliée. */
    static final Duration KEEP = Duration.ofMinutes(15);
    static final int MAX_BODY = 16 * 1024;
    private static final Map<String, String> FILES = Map.of(
            "/", "index.html",
            "/interface.js", "interface.js",
            "/interface.css", "interface.css");

    private final Path keys;
    private final Path codesFile;
    private final DirectoryCalls calls;
    private final long days;
    private final Clock clock;
    private final PrintStream out;
    private final char[] environmentPassphrase;
    private final KeyFiles.OrganizationPublicKey publicKey;
    private final HttpServer server;
    private final String token;
    private final CountDownLatch closed = new CountDownLatch(1);
    private Organization kept;
    private Instant keptUntil = Instant.MIN;

    /** Refus à renvoyer à la page, avec son statut HTTP. */
    static final class Refusal extends RuntimeException {
        final int status;

        Refusal(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private LocalInterface(Path keys, Path codesFile, DirectoryCalls calls, long days, int port, Clock clock,
                           PrintStream out, char[] environmentPassphrase) throws IOException {
        this.keys = keys;
        this.codesFile = codesFile;
        this.calls = calls;
        this.days = days;
        this.clock = clock;
        this.out = out;
        this.environmentPassphrase = environmentPassphrase;
        this.publicKey = KeyFiles.readOrganizationPublic(Path.of(keys + ".pub.json"));
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        this.token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /**
     * Démarre l'interface.
     *
     * @param environmentPassphrase phrase de passe de {@code IMAGUNITY_PHRASE_ORGANISATION}, ou {@code null}
     */
    static LocalInterface start(Path keys, Path codesFile, DirectoryCalls calls, long days, int port, Clock clock,
                                PrintStream out, char[] environmentPassphrase) throws IOException {
        return new LocalInterface(keys, codesFile, calls, days, port, clock, out, environmentPassphrase);
    }

    /** Adresse à ouvrir dans le navigateur de ce poste, jeton compris (dans le fragment). */
    URI url() {
        return URI.create("http://127.0.0.1:" + port() + "/#jeton=" + token);
    }

    int port() {
        return server.getAddress().getPort();
    }

    String token() {
        return token;
    }

    /** Attend l'arrêt demandé depuis la page. */
    void awaitClose() throws InterruptedException {
        closed.await();
    }

    @Override
    public void close() {
        forget();
        server.stop(0);
        closed.countDown();
    }

    // --- HTTP ---

    private void handle(HttpExchange exchange) throws IOException {
        boolean quit = false;
        try (exchange) {
            var headers = exchange.getResponseHeaders();
            headers.set("Cache-Control", "no-store");
            headers.set("X-Content-Type-Options", "nosniff");
            headers.set("X-Frame-Options", "DENY");
            headers.set("Referrer-Policy", "no-referrer");
            headers.set("Content-Security-Policy", "default-src 'self'; frame-ancestors 'none'; form-action 'none'");
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!("127.0.0.1:" + port()).equals(host) && !("localhost:" + port()).equals(host)) {
                send(exchange, 403, "text/plain", "Adresse inattendue");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if (!path.startsWith("/api/")) {
                String file = FILES.get(path);
                if (file == null || !method.equals("GET")) {
                    send(exchange, 404, "text/plain", "Introuvable");
                    return;
                }
                try (InputStream in = LocalInterface.class.getResourceAsStream("interface/" + file)) {
                    send(exchange, 200, file.endsWith(".html") ? "text/html" : file.endsWith(".js")
                            ? "text/javascript" : "text/css", new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
                return;
            }
            String presented = exchange.getRequestHeaders().getFirst("X-Jeton");
            if (presented == null || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                send(exchange, 401, "application/json", error("Jeton absent ou incorrect : rouvrir l'adresse "
                        + "affichée par l'outil"));
                return;
            }
            JsonNode body = null;
            if (method.equals("POST")) {
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                if (type == null || !type.startsWith("application/json")) {
                    send(exchange, 415, "application/json", error("Corps JSON attendu"));
                    return;
                }
                byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY + 1);
                if (bytes.length > MAX_BODY) {
                    exchange.getRequestBody().skip(1 << 20);
                    send(exchange, 413, "application/json", error("Corps trop long"));
                    return;
                }
                try {
                    body = bytes.length == 0 ? Json.MAPPER.createObjectNode()
                            : Json.MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
                } catch (tools.jackson.core.JacksonException e) {
                    send(exchange, 400, "application/json", error("Corps JSON illisible"));
                    return;
                }
            }
            Object result;
            try {
                result = dispatch(method + " " + path, body);
            } catch (Refusal e) {
                send(exchange, e.status, "application/json", error(e.getMessage()));
                return;
            } catch (DirectoryException | IllegalArgumentException e) {
                send(exchange, 422, "application/json", error(e.getMessage()));
                return;
            } catch (IOException e) {
                send(exchange, 502, "application/json", error("Annuaire injoignable (" + calls.directory() + ") : "
                        + e.getMessage()));
                return;
            }
            send(exchange, 200, "application/json", Json.write(result));
            quit = path.equals("/api/quitter");
        }
        if (quit) {
            // Après l'envoi complet de la réponse : la page apprend que l'interface s'arrête.
            Thread.ofPlatform().start(this::close);
        }
    }

    private Object dispatch(String call, JsonNode body) throws IOException {
        return switch (call) {
            case "GET /api/etat" -> state();
            case "GET /api/demandes" -> pending();
            case "POST /api/valider" -> validate(body);
            case "POST /api/refuser" -> refuse(body);
            case "GET /api/box" -> boxes();
            case "POST /api/renouveler" -> renew(body);
            case "POST /api/revoquer" -> revoke(body);
            case "GET /api/codes" -> codes();
            case "POST /api/codes" -> createCode(body);
            case "POST /api/codes/retirer" -> removeCode(body);
            case "GET /api/partenaires" -> partners();
            case "POST /api/partenaires/cle" -> partnerKey(body);
            case "POST /api/partenaires" -> recognize(body);
            case "POST /api/partenaires/renouveler" -> renewRecognition(body);
            case "POST /api/partenaires/revoquer" -> revokeRecognition(body);
            case "POST /api/oublier" -> {
                forget();
                yield state();
            }
            case "POST /api/quitter" -> Map.of("message", "Interface arrêtée : vous pouvez fermer cette page");
            default -> throw new Refusal(404, "Appel inconnu : " + call);
        };
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static String error(String message) {
        return Json.write(Map.of("erreur", message));
    }

    // --- Appels ---

    private Map<String, Object> state() throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("organisation", publicKey.organization());
        state.put("empreinteOrganisation", publicKey.key().shortCode());
        state.put("annuaire", calls.directory());
        state.put("validiteJours", days);
        state.put("renouvelerAvantJours", RENEW_BEFORE.toDays());
        state.put("phrase", phraseState());
        return state;
    }

    /** {@code inutile} (clé en clair), {@code environnement}, {@code gardee} ou {@code requise}. */
    private String phraseState() throws IOException {
        if (!KeyFiles.isProtected(keys)) {
            return "inutile";
        }
        if (environmentPassphrase != null) {
            return "environnement";
        }
        return keptKey().isPresent() ? "gardee" : "requise";
    }

    private List<Map<String, Object>> pending() throws IOException {
        EnrollmentCodes codes = EnrollmentCodes.read(codesFile);
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode item : listed()) {
            Optional<PendingRequest> assessed = PendingRequest.assess(item, codes, clock.instant());
            if (assessed.isEmpty()) {
                continue;
            }
            PendingRequest p = assessed.get();
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("boxId", p.boxId().toString());
            d.put("statut", p.status().label);
            d.put("detail", p.detail());
            d.put("recueLe", item.path("receivedAt").isMissingNode() ? null : item.get("receivedAt").asString());
            if (p.request() != null) {
                d.put("nomPropose", p.request().displayName());
                d.put("empreinte", p.fingerprint());
            }
            if (p.code() != null && p.status() == PendingRequest.Status.CODE_VERIFIE) {
                d.put("nom", p.code().name());
                d.put("groupes", p.code().groups());
            }
            list.add(d);
        }
        return list;
    }

    private JsonNode listed() throws IOException {
        HttpResponse<String> response = calls.get("/directory/v1/enrollment-requests", true);
        if (response.statusCode() != 200) {
            throw new Refusal(502, "Lecture des demandes refusée par l'annuaire (HTTP " + response.statusCode()
                    + ")" + (response.statusCode() == 401 ? " : compte du responsable incorrect" : ""));
        }
        return Json.MAPPER.readTree(response.body());
    }

    /** Demande en attente de cette box, réexaminée à l'instant : rien n'est repris de la page. */
    private PendingRequest current(JsonNode body) throws IOException {
        UUID boxId = boxId(body);
        EnrollmentCodes codes = EnrollmentCodes.read(codesFile);
        for (JsonNode item : listed()) {
            Optional<PendingRequest> p = PendingRequest.assess(item, codes, clock.instant());
            if (p.isPresent() && p.get().boxId().equals(boxId)) {
                return p.get();
            }
        }
        throw new Refusal(404, "Plus de demande en attente pour la box " + boxId + " : déjà traitée ?");
    }

    private static UUID boxId(JsonNode body) {
        try {
            return UUID.fromString(body.path("boxId").asString());
        } catch (IllegalArgumentException e) {
            throw new Refusal(400, "Identifiant de box manquant ou mal formé");
        }
    }

    private Map<String, Object> validate(JsonNode body) throws IOException {
        PendingRequest p = current(body);
        if (!p.signable()) {
            throw new Refusal(409, "Validation impossible : " + p.detail());
        }
        if (!p.fingerprint().equals(body.path("empreinte").asString())) {
            throw new Refusal(409, "La demande de cette box a changé depuis l'affichage : actualiser et comparer "
                    + "à nouveau l'empreinte");
        }
        String name;
        List<String> groups;
        if (p.status() == PendingRequest.Status.CODE_VERIFIE) {
            name = p.code().name();
            groups = p.code().groups();
        } else {
            if (!body.path("empreinteVerifiee").asBoolean(false)) {
                throw new Refusal(422, "Demande sans code : comparer d'abord l'empreinte avec l'installateur");
            }
            String chosen = body.path("nom").asString("").strip();
            name = chosen.isEmpty() ? p.request().displayName() : chosen;
            if (name.length() > 256) {
                throw new Refusal(422, "Nom de box trop long (256 caractères au plus)");
            }
            List<String> asked = new ArrayList<>();
            body.path("groupes").forEach(g -> asked.add(g.asString().strip()));
            groups = BoxEntry.groups(asked.stream().filter(g -> !g.isEmpty()).toList());
        }
        Organization organization = organization(body.path("phrase").asString(""), body.path("garder")
                .asBoolean(false));
        long serial = calls.nextSerial(p.boxId());
        SignedDocument entry = organization.enroll(p.submission().request(), clock.instant(), Duration.ofDays(days),
                serial, groups, name);
        HttpResponse<String> published = calls.post("/directory/v1/entries", entry);
        if (published.statusCode() != 201 && published.statusCode() != 200) {
            throw new Refusal(502, "Publication refusée par l'annuaire (HTTP " + published.statusCode() + ") : "
                    + published.body());
        }
        if (p.code() != null) {
            EnrollmentCodes codes = EnrollmentCodes.read(codesFile);
            codes.replace(p.code(), p.code().used(p.boxId(), clock.instant())).write(codesFile);
        }
        String message = "Box « " + name + " » enrôlée et publiée (n° " + serial + ", " + days + " jours"
                + (groups.isEmpty() ? "" : ", groupes " + String.join(", ", groups)) + ")";
        out.println(message + " : " + p.boxId());
        return Map.of("message", message, "phrase", phraseState());
    }

    private Map<String, Object> refuse(JsonNode body) throws IOException {
        UUID boxId = boxId(body);
        HttpResponse<String> response = calls.delete("/directory/v1/enrollment-requests/" + boxId);
        if (response.statusCode() == 404) {
            throw new Refusal(404, "Plus de demande en attente pour la box " + boxId + " : déjà traitée ?");
        }
        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new Refusal(502, "Refus non enregistré par l'annuaire (HTTP " + response.statusCode() + ")");
        }
        out.println("Demande de la box " + boxId + " refusée");
        return Map.of("message", "Demande refusée : les nouveaux envois de cette box seront ignorés 30 jours");
    }

    // --- Box de l'organisation : renouvellement, révocation ---

    /** Entrée publiée d'une box, vérifiée avec la clé de l'organisation. */
    private record Published(SignedDocument document, BoxEntry entry) {
    }

    /** Entrées publiées dans l'annuaire et signées par cette organisation (les autres sont ignorées). */
    private Map<UUID, Published> published() throws IOException {
        HttpResponse<String> response = calls.read("/directory/v1/entries");
        if (response.statusCode() != 200) {
            throw new Refusal(502, "Lecture de l'annuaire refusée (HTTP " + response.statusCode() + ")");
        }
        EntryVerifier verifier = new EntryVerifier(publicKey.organization(), publicKey.key(),
                SerialMemory.inMemory(), clock);
        Map<UUID, Published> all = new LinkedHashMap<>();
        for (JsonNode item : Json.MAPPER.readTree(response.body()).path("entries")) {
            try {
                SignedDocument document = SignedDocument.fromJson(item.toString());
                BoxEntry entry = verifier.verify(document).entry();
                all.put(entry.boxId(), new Published(document, entry));
            } catch (DirectoryException | IllegalArgumentException e) {
                // entrée d'un autre responsable, ou altérée : ni montrée ni réémise
            }
        }
        return all;
    }

    private List<Map<String, Object>> boxes() throws IOException {
        Instant now = clock.instant();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Published p : published().values()) {
            BoxEntry e = p.entry();
            BoxEntry.Status status = e.statusAt(now);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("boxId", e.boxId().toString());
            b.put("nom", e.displayName());
            b.put("groupes", e.groups());
            b.put("statut", status.name());
            b.put("serie", e.serial());
            b.put("valideDu", e.validFrom().toString());
            b.put("valideJusquau", e.validUntil().toString());
            b.put("empreinte", HybridPublicKey.decode(e.signingKeys()).shortCode());
            if (e.revoked()) {
                b.put("revoqueeLe", e.revokedAt().toString());
                b.put("motif", e.revocationReason());
            }
            b.put("aRenouveler", status == BoxEntry.Status.EXPIRED || status == BoxEntry.Status.ACTIVE
                    && e.validUntil().isBefore(now.plus(RENEW_BEFORE)));
            list.add(b);
        }
        list.sort(java.util.Comparator.comparing(b -> ((String) b.get("nom")).toLowerCase(java.util.Locale.ROOT)));
        return list;
    }

    private static List<UUID> boxIds(JsonNode body) {
        List<UUID> ids = new ArrayList<>();
        try {
            body.path("boxIds").forEach(id -> ids.add(UUID.fromString(id.asString())));
        } catch (IllegalArgumentException e) {
            throw new Refusal(400, "Identifiant de box mal formé");
        }
        if (ids.isEmpty() || ids.size() > 500) {
            throw new Refusal(400, "Choisir de 1 à 500 box à renouveler");
        }
        return ids;
    }

    /** Renouvelle les entrées choisies (mêmes clés, adresses et groupes), relues dans l'annuaire à l'instant. */
    private Map<String, Object> renew(JsonNode body) throws IOException {
        List<UUID> ids = boxIds(body);
        Map<UUID, Published> all = published();
        for (UUID id : ids) {
            Published p = all.get(id);
            if (p == null) {
                throw new Refusal(404, "Box " + id + " absente de l'annuaire");
            }
            if (p.entry().revoked()) {
                throw new Refusal(409, "Box « " + p.entry().displayName() + " » révoquée : pas de renouvellement");
            }
        }
        Organization organization = organization(body.path("phrase").asString(""), body.path("garder")
                .asBoolean(false));
        List<String> done = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (UUID id : ids) {
            Published p = all.get(id);
            HttpResponse<String> response = calls.post("/directory/v1/entries",
                    organization.renew(p.document(), clock.instant(), Duration.ofDays(days)));
            if (response.statusCode() == 201 || response.statusCode() == 200) {
                done.add(p.entry().displayName());
            } else {
                failed.add(p.entry().displayName() + " (HTTP " + response.statusCode() + " : " + response.body()
                        + ")");
            }
        }
        String message = done.size() + " entrée(s) renouvelée(s) pour " + days + " jours"
                + (failed.isEmpty() ? "" : " ; refusée(s) par l'annuaire : " + String.join(", ", failed));
        out.println(message);
        if (done.isEmpty()) {
            throw new Refusal(502, message);
        }
        return Map.of("message", message, "phrase", phraseState());
    }

    /** Révoque une box : entrée de numéro suivant, marquée révoquée, avec le motif. */
    private Map<String, Object> revoke(JsonNode body) throws IOException {
        UUID boxId = boxId(body);
        String reason = body.path("motif").asString("").strip();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new Refusal(422, "Motif de révocation obligatoire (500 caractères au plus)");
        }
        Published p = published().get(boxId);
        if (p == null) {
            throw new Refusal(404, "Box " + boxId + " absente de l'annuaire");
        }
        if (p.entry().revoked()) {
            throw new Refusal(409, "Box « " + p.entry().displayName() + " » déjà révoquée");
        }
        if (!p.entry().displayName().equals(body.path("confirmation").asString(""))) {
            throw new Refusal(422, "Recopier exactement le nom de la box pour confirmer la révocation");
        }
        Organization organization = organization(body.path("phrase").asString(""), body.path("garder")
                .asBoolean(false));
        HttpResponse<String> response = calls.post("/directory/v1/entries",
                organization.revoke(p.document(), clock.instant(), reason));
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new Refusal(502, "Révocation refusée par l'annuaire (HTTP " + response.statusCode() + ") : "
                    + response.body());
        }
        String message = "Box « " + p.entry().displayName() + " » révoquée : les autres box cessent d'échanger avec "
                + "elle à leur prochaine lecture de l'annuaire";
        out.println(message + " (" + boxId + ", motif : " + reason + ")");
        return Map.of("message", message, "phrase", phraseState());
    }

    // --- Codes d'enrôlement ---

    private List<Map<String, Object>> codes() throws IOException {
        Instant now = clock.instant();
        List<Map<String, Object>> list = new ArrayList<>();
        for (EnrollmentCodes.Entry c : EnrollmentCodes.read(codesFile).codes()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", EnrollmentCode.id(c.code()));
            m.put("nom", c.name());
            m.put("groupes", c.groups());
            m.put("creeLe", c.createdAt().toString());
            m.put("expireLe", c.expiresAt().toString());
            if (c.usedAt() != null) {
                m.put("statut", "utilise");
                m.put("utiliseLe", c.usedAt().toString());
                m.put("boxId", c.boxId().toString());
            } else if (!now.isBefore(c.expiresAt())) {
                m.put("statut", "expire");
            } else {
                m.put("statut", "libre");
                m.put("code", c.code());
            }
            list.add(m);
        }
        java.util.Collections.reverse(list);
        return list;
    }

    private Map<String, Object> createCode(JsonNode body) throws IOException {
        List<String> groups = new ArrayList<>();
        body.path("groupes").forEach(g -> groups.add(g.asString().strip()));
        EnrollmentCodes.Entry entry = EnrollmentCodes.create(body.path("nom").asString(""),
                groups.stream().filter(g -> !g.isEmpty()).toList(), body.path("validiteJours").asLong(30),
                clock.instant());
        EnrollmentCodes.read(codesFile).with(entry).write(codesFile);
        out.println("Code d'enrôlement tiré pour la box « " + entry.name() + " », valable jusqu'au "
                + entry.expiresAt().atZone(java.time.ZoneOffset.UTC).toLocalDate());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("message", "Code tiré pour la box « " + entry.name() + " » : à transmettre hors bande à l'installateur");
        m.put("code", entry.code());
        m.put("nom", entry.name());
        m.put("groupes", entry.groups());
        m.put("expireLe", entry.expiresAt().toString());
        m.put("ligne", "IMAGUNITY_DIRECTORY_ENROLLMENT_CODE=" + entry.code());
        return m;
    }

    /** Retire un code pas encore utilisé : une box qui le présenterait n'est plus reconnue. */
    private Map<String, Object> removeCode(JsonNode body) throws IOException {
        String id = body.path("id").asString("");
        EnrollmentCodes codes = EnrollmentCodes.read(codesFile);
        EnrollmentCodes.Entry entry = codes.byId(id).orElseThrow(() -> new Refusal(404, "Code inconnu"));
        if (entry.usedAt() != null) {
            throw new Refusal(409, "Code déjà utilisé par la box " + entry.boxId() + " : gardé pour mémoire");
        }
        codes.without(entry).write(codesFile);
        out.println("Code d'enrôlement de la box « " + entry.name() + " » retiré");
        return Map.of("message", "Code de la box « " + entry.name() + " » retiré");
    }

    // --- Organisations partenaires (fédération) ---

    /** Reconnaissance publiée, vérifiée avec la clé publique de l'organisation. */
    private record PublishedRecognition(Recognition recognition, SignedDocument document) {
    }

    private List<PublishedRecognition> recognitions() throws IOException {
        HttpResponse<String> response = calls.read("/directory/v1/recognitions");
        if (response.statusCode() == 404) {
            return List.of();
        }
        if (response.statusCode() != 200) {
            throw new Refusal(502, "Lecture des reconnaissances refusée par l'annuaire (HTTP " + response.statusCode()
                    + ")");
        }
        List<PublishedRecognition> list = new ArrayList<>();
        for (JsonNode item : Json.MAPPER.readTree(response.body()).path("recognitions")) {
            try {
                SignedDocument document = SignedDocument.fromJson(item.toString());
                list.add(new PublishedRecognition(new RecognitionVerifier(publicKey.organization(), publicKey.key(),
                        SerialMemory.inMemory()).verify(document), document));
            } catch (DirectoryException | IllegalArgumentException e) {
                // publiée par une autre clé, ou altérée : ni montrée ni réémise
            }
        }
        return list;
    }

    private PublishedRecognition recognition(JsonNode body) throws IOException {
        String fingerprint = body.path("empreinte").asString("");
        return recognitions().stream().filter(r -> r.recognition().partner().fingerprint().equals(fingerprint))
                .findFirst().orElseThrow(() -> new Refusal(404, "Organisation partenaire inconnue"));
    }

    private List<Map<String, Object>> partners() throws IOException {
        Instant now = clock.instant();
        List<Map<String, Object>> list = new ArrayList<>();
        for (PublishedRecognition p : recognitions()) {
            Recognition r = p.recognition();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("organisation", r.partner().organization());
            m.put("empreinte", r.partner().fingerprint());
            m.put("empreinteCourte", r.partner().shortCode());
            m.put("statut", r.statusAt(now).name());
            m.put("serie", r.serial());
            m.put("valideJusquau", r.validUntil().toString());
            m.put("annuaire", r.partner().directory());
            m.put("relais", r.partner().relays());
            m.put("groupesLocaux", r.scope().localGroups());
            m.put("groupesPartenaire", r.scope().partnerGroups());
            if (r.revoked()) {
                m.put("revoqueeLe", r.revokedAt().toString());
                m.put("motif", r.revocationReason());
            }
            list.add(m);
        }
        list.sort(java.util.Comparator.comparing(m -> (String) m.get("organisation")));
        return list;
    }

    private static KeyFiles.OrganizationPublicKey pastedKey(JsonNode body) {
        String text = body.path("cle").asString("");
        if (text.isBlank()) {
            throw new Refusal(422, "Coller le contenu du fichier de clé publique du partenaire (.pub.json)");
        }
        try {
            return KeyFiles.parseOrganizationPublic(text);
        } catch (DirectoryException | IllegalArgumentException e) {
            throw new Refusal(422, "Clé publique du partenaire illisible : " + e.getMessage());
        }
    }

    /** Organisation et empreinte de la clé collée, à comparer hors bande avant de signer. */
    private Map<String, Object> partnerKey(JsonNode body) {
        KeyFiles.OrganizationPublicKey key = pastedKey(body);
        if (key.key().fingerprint().equals(publicKey.key().fingerprint())) {
            throw new Refusal(422, "C'est la clé de cette organisation");
        }
        return Map.of("organisation", key.organization(), "empreinteCourte", key.key().shortCode(),
                "empreinte", key.key().fingerprint());
    }

    private static List<String> list(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(v -> values.add(v.asString().strip()));
        return values.stream().filter(v -> !v.isEmpty()).toList();
    }

    /** Reconnaît une organisation (ou réémet sa reconnaissance), après confirmation de son empreinte. */
    private Map<String, Object> recognize(JsonNode body) throws IOException {
        KeyFiles.OrganizationPublicKey key = pastedKey(body);
        if (!key.key().fingerprint().equals(body.path("empreinte").asString(""))) {
            throw new Refusal(409, "La clé collée a changé depuis l'affichage de son empreinte");
        }
        if (!body.path("empreinteVerifiee").asBoolean(false)) {
            throw new Refusal(422, "Comparer d'abord l'empreinte avec le responsable partenaire, hors bande");
        }
        Recognition.Partner partner;
        Recognition.Scope scope;
        try {
            partner = new Recognition.Partner(key.organization(), key.signingKeys(), body.path("annuaire")
                    .asString(""), list(body.path("relais")));
            scope = new Recognition.Scope(list(body.path("groupesLocaux")), list(body.path("groupesPartenaire")));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new Refusal(422, e.getMessage());
        }
        Optional<PublishedRecognition> current = recognitions().stream()
                .filter(r -> r.recognition().partner().fingerprint().equals(partner.fingerprint())).findFirst();
        Organization organization = organization(body.path("phrase").asString(""), body.path("garder")
                .asBoolean(false));
        SignedDocument document = current.isEmpty()
                ? organization.recognize(partner, scope, clock.instant(), Duration.ofDays(days))
                : organization.renewRecognition(current.get().document(), partner, scope, clock.instant(),
                Duration.ofDays(days));
        publishRecognition(document);
        String message = "Organisation « " + partner.organization() + " » reconnue (" + days + " jours)"
                + (scope.empty() ? " ; périmètre vide : aucun échange tant que des groupes ne sont pas désignés" : "")
                + ". L'échange ne marchera qu'une fois cette organisation reconnue à son tour par le partenaire";
        out.println(message);
        return Map.of("message", message, "phrase", phraseState());
    }

    private Map<String, Object> renewRecognition(JsonNode body) throws IOException {
        PublishedRecognition p = recognition(body);
        Recognition r = p.recognition();
        Organization organization = organization(body.path("phrase").asString(""), body.path("garder")
                .asBoolean(false));
        publishRecognition(organization.renewRecognition(p.document(), r.partner(), r.scope(), clock.instant(),
                Duration.ofDays(days)));
        String message = "Reconnaissance de « " + r.partner().organization() + " » renouvelée pour " + days + " jours";
        out.println(message);
        return Map.of("message", message, "phrase", phraseState());
    }

    private Map<String, Object> revokeRecognition(JsonNode body) throws IOException {
        PublishedRecognition p = recognition(body);
        Recognition r = p.recognition();
        String reason = body.path("motif").asString("").strip();
        if (reason.isEmpty() || reason.length() > 500) {
            throw new Refusal(422, "Motif de révocation obligatoire (500 caractères au plus)");
        }
        if (r.revoked()) {
            throw new Refusal(409, "Reconnaissance déjà révoquée");
        }
        if (!r.partner().organization().equals(body.path("confirmation").asString(""))) {
            throw new Refusal(422, "Recopier exactement le nom de l'organisation pour confirmer la révocation");
        }
        Organization organization = organization(body.path("phrase").asString(""), body.path("garder")
                .asBoolean(false));
        publishRecognition(organization.revokeRecognition(p.document(), clock.instant(), reason));
        String message = "Reconnaissance de « " + r.partner().organization() + " » révoquée : les échanges avec ses "
                + "box cessent à la prochaine lecture de l'annuaire";
        out.println(message);
        return Map.of("message", message, "phrase", phraseState());
    }

    private void publishRecognition(SignedDocument document) throws IOException {
        HttpResponse<String> response = calls.post("/directory/v1/recognitions", document);
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new Refusal(502, "Publication refusée par l'annuaire (HTTP " + response.statusCode() + ") : "
                    + response.body());
        }
    }

    // --- Clé de l'organisation ---

    private Optional<Organization> keptKey() {
        if (kept != null && clock.instant().isBefore(keptUntil)) {
            return Optional.of(kept);
        }
        kept = null;
        return Optional.empty();
    }

    private void forget() {
        kept = null;
        keptUntil = Instant.MIN;
    }

    /** Clé de l'organisation : gardée, ou déchiffrée avec la phrase saisie (ou celle de l'environnement). */
    private Organization organization(String typed, boolean keep) throws IOException {
        Optional<Organization> already = keptKey();
        if (already.isPresent()) {
            keptUntil = clock.instant().plus(KEEP);
            return already.get();
        }
        HybridKeyPair pair;
        if (!KeyFiles.isProtected(keys)) {
            pair = KeyFiles.readPrivate(keys);
        } else {
            char[] passphrase = !typed.isEmpty() ? typed.toCharArray()
                    : environmentPassphrase == null ? null : environmentPassphrase.clone();
            if (passphrase == null) {
                throw new Refusal(422, "Phrase de passe de la clé du responsable requise");
            }
            try {
                pair = KeyFiles.readPrivate(keys, passphrase);
            } catch (DirectoryException e) {
                throw new Refusal(403, e.getMessage());
            } finally {
                Arrays.fill(passphrase, '\0');
            }
        }
        if (!pair.publicKey().fingerprint().equals(publicKey.key().fingerprint())) {
            throw new Refusal(422, "La clé privée ne correspond pas à la clé publique de l'organisation");
        }
        Organization organization = new Organization(publicKey.organization(), pair);
        if (keep) {
            kept = organization;
            keptUntil = clock.instant().plus(KEEP);
        }
        return organization;
    }
}
