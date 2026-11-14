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
