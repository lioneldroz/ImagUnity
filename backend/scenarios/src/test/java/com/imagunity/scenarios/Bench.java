package com.imagunity.scenarios;

import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SignedDocument;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Banc de scénarios : une organisation complète, dans la JVM des tests.
 *
 * <ul>
 *   <li>le responsable de l'organisation (sa paire de clés, qui signe les entrées et les révocations) ;</li>
 *   <li>le vrai service d'annuaire (base H2 en mémoire) ;</li>
 *   <li>un vrai relais IU-T (base H2 en mémoire, morceaux sur disque), qui ne prend les dépôts que des box
 *       enrôlées et actives ;</li>
 *   <li>des box réelles ({@link Box}), chacune avec son PACS simulé de restitution ({@link FakePacs}) ; leurs bases et
 *       leurs dossiers sont sur disque, si bien qu'une box arrêtée puis redémarrée reprend son état.</li>
 * </ul>
 *
 * <p>Tout est réel sauf les PACS : entrée DICOM et DICOMweb, empaquetage, chiffrement, transport par le relais,
 * annuaire signé, restitution en C-STORE. Les échanges passent par HTTP et DICOM sur la boucle locale. Données
 * synthétiques uniquement ; mots de passe d'essai.
 */
final class Bench implements AutoCloseable {

    static final String CONSOLE_USER = "operateur";
    static final String CONSOLE_PASSWORD = "console-scenario";
    static final String DICOMWEB_USER = "pacs";
    static final String DICOMWEB_PASSWORD = "dicomweb-scenario";
    static final String CONSOLE_HEADER = "X-ImagUnity-Console";
    private static final String PUBLISHER = "responsable:depot-scenario";

    /** Attente maximale d'une condition (transfert, synchronisation de l'annuaire…). */
    static final Duration TIMEOUT = Duration.ofSeconds(60);

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    final Path dir;
    final Organization organization = new Organization("chu-scenarios", HybridKeyPair.generate());
    private final Path organizationKey;
    private final ConfigurableApplicationContext directory;
    final URI directoryUrl;
    private ConfigurableApplicationContext relay;
    URI relayUrl;
    private final Path relayStorage;
    private final String relayDatabase = "relais-" + UUID.randomUUID();
    private final Map<String, Box> boxes = new LinkedHashMap<>();
    /** Dernière entrée publiée de chaque box. */
    private final Map<UUID, SignedDocument> entries = new LinkedHashMap<>();
    private final Map<UUID, Long> serials = new LinkedHashMap<>();

    Bench() {
        try {
            dir = Files.createTempDirectory("imagunity-scenario");
            organizationKey = dir.resolve("organisation.pub.json");
            KeyFiles.writeOrganizationPublic(organizationKey, organization.id(), organization.publicKey());
            relayStorage = Files.createDirectories(dir.resolve("relais/morceaux"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        directory = startDirectory();
        directoryUrl = url(directory);
        startRelay();
    }

    String organizationCode() {
        return organization.publicKey().shortCode();
    }

    // --- Service d'annuaire et relais ---

    private ConfigurableApplicationContext startDirectory() {
        Path conf = dir.resolve("conf/annuaire");
        Class<?> app = AppFiles.load("com.imagunity.directoryserver.DirectoryServerApplication");
        Path yml = AppFiles.copy(app, "application.yml", conf);
        Path migrations = AppFiles.copy(app, "db/migration", conf);
        return new SpringApplicationBuilder(app).run(
                "--spring.config.location=file:" + yml,
                "--spring.flyway.locations=filesystem:" + migrations,
                "--spring.jmx.enabled=false",
                "--server.port=0", "--server.address=127.0.0.1",
                "--spring.datasource.url=jdbc:h2:mem:annuaire-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--imagunity.directory.organization-key=" + organizationKey,
                "--imagunity.directory.organization-code=" + organizationCode(),
                "--imagunity.directory.publisher-password=" + PUBLISHER.substring(PUBLISHER.indexOf(':') + 1),
                "--imagunity.directory.generated-password-file=" + dir.resolve("annuaire-mot-de-passe.txt"));
    }

    /** Démarre le relais (ou le redémarre après {@link #stopRelay()}), avec son état : base et morceaux. */
    void startRelay() {
        if (relay != null) {
            return;
        }
        Path conf = dir.resolve("conf/relais");
        Class<?> app = AppFiles.load("com.imagunity.relay.RelayApplication");
        Path yml = AppFiles.copy(app, "application.yml", conf);
        Path migrations = AppFiles.copy(app, "db/migration", conf);
        String port = relayUrl == null ? "0" : String.valueOf(relayUrl.getPort());
        relay = new SpringApplicationBuilder(app, RelaySecurity.class).run(
                "--spring.config.location=file:" + yml,
                "--spring.flyway.locations=filesystem:" + migrations,
                "--spring.jmx.enabled=false",
                "--server.port=" + port, "--server.address=127.0.0.1",
                // Base en mémoire gardée tant que la JVM vit : un relais redémarré retrouve ses files et morceaux.
                "--spring.datasource.url=jdbc:h2:mem:" + relayDatabase + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--imagunity.relay.instance-id=relais-scenario",
                "--imagunity.relay.storage-dir=" + relayStorage,
                "--imagunity.relay.min-free-space=1MB",
                "--imagunity.relay.max-wait=2s",
                "--imagunity.relay.max-queues-per-box=20",
                "--imagunity.relay.directory.url=" + directoryUrl,
                "--imagunity.relay.directory.organization-key=" + organizationKey,
                "--imagunity.relay.directory.organization-code=" + organizationCode(),
                "--imagunity.relay.directory.sync-interval=1s",
                "--spring.lifecycle.timeout-per-shutdown-phase=2s");
        relayUrl = url(relay);
    }

    /** Arrête le relais (panne) : les box ne peuvent plus déposer ni relever. */
    void stopRelay() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
    }

    // --- Box ---

    /** Démarre une box (première fois : identité créée) ; voir {@link Box.Spec}. */
    Box box(Box.Spec spec) {
        Box box = new Box(this, spec);
        boxes.put(spec.name(), box);
        box.start();
        return box;
    }

    /** Fait signer par le responsable, puis publie, l'entrée d'annuaire de la box (enrôlement ou réémission). */
    void enroll(Box box) {
        enroll(box, Duration.ofDays(30));
    }

    /** Comme {@link #enroll(Box)}, pour une entrée valable encore {@code remaining} à partir de maintenant. */
    void enroll(Box box, Duration remaining) {
        SignedDocument request = awaitValue(() -> {
            SignedDocument r = SignedDocument.fromJson(box.get("/api/v1/directory/enrollment-request?name="
                    + box.spec.name()).body());
            // La demande porte l'adresse de la file de réception de la box sur le relais : attendre qu'elle y soit.
            return EnrollmentRequest.verify(r).transport().isEmpty() ? null : r;
        }, "demande d'enrôlement de " + box.spec.name() + " avec son adresse IU-T");
        long serial = serials.merge(box.boxId(), 1L, Long::sum);
        // Entrée valable depuis une minute : les horloges du banc n'ont pas à être exactement d'accord.
        SignedDocument entry = organization.enroll(request, Instant.now().minusSeconds(60),
                remaining.plusSeconds(60), serial);
        publish(entry);
        entries.put(box.boxId(), entry);
    }

    /** Renouvelle l'entrée de la box (mêmes clés et adresses, numéro suivant), valable encore {@code remaining}. */
    void renew(Box box, Duration remaining) {
        SignedDocument renewed = organization.renew(entries.get(box.boxId()), Instant.now(), remaining);
        publish(renewed);
        entries.put(box.boxId(), renewed);
    }

    /** Révoque la box dans l'annuaire (entrée de révocation signée par le responsable, numéro suivant). */
    void revoke(Box box, String reason) {
        SignedDocument revocation = organization.revoke(entries.get(box.boxId()), Instant.now(), reason);
        publish(revocation);
        entries.put(box.boxId(), revocation);
    }

    private void publish(SignedDocument document) {
        HttpResponse<String> response = send(HttpRequest.newBuilder(directoryUrl.resolve("/directory/v1/entries"))
                .header("Authorization", basic(PUBLISHER))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(document.toJson())));
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Publication refusée par l'annuaire : " + response.statusCode() + " "
                    + response.body());
        }
    }

    /**
     * Appaire {@code from} à {@code to} par l'annuaire, sous le nom {@code name} chez {@code from}, et attend que la
     * poignée de main signée ait lié les deux box.
     */
    void pair(Box from, Box to, String name) {
        await(() -> from.directoryView(to.boxId()).map(b -> b.path("reachable").asBoolean()
                        && "ACTIVE".equals(b.path("status").asString())).orElse(false),
                from.spec.name() + " voit " + to.spec.name() + " active dans l'annuaire");
        HttpResponse<String> response = from.post("/api/v1/directory/boxes/" + to.boxId() + "/pair",
                "{\"name\":\"" + name + "\"}");
        if (response.statusCode() != 202) {
            throw new IllegalStateException("Appairage refusé : " + response.statusCode() + " " + response.body());
        }
        await(() -> from.peerBoundTo(to.boxId()) && to.peerBoundTo(from.boxId()),
                "poignée de main entre " + from.spec.name() + " et " + to.spec.name());
    }

    // --- Outils ---

    static String basic(String account) {
        return "Basic " + Base64.getEncoder().encodeToString(account.getBytes(StandardCharsets.UTF_8));
    }

    static HttpResponse<String> send(HttpRequest.Builder request) {
        try {
            return HTTP.send(request.timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    static URI url(ConfigurableApplicationContext context) {
        return URI.create("http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port"));
    }

    /** Attend que la condition soit vraie ({@link #TIMEOUT} au plus). */
    static void await(BooleanSupplier condition, String what) {
        await(condition, what, TIMEOUT);
    }

    static void await(BooleanSupplier condition, String what, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        RuntimeException last = null;
        while (Instant.now().isBefore(deadline)) {
            try {
                if (condition.getAsBoolean()) {
                    return;
                }
                last = null;
            } catch (RuntimeException e) {
                last = e; // service pas encore prêt : nouvel essai
            }
            sleep(Duration.ofMillis(200));
        }
        throw new AssertionError("Délai dépassé (" + timeout.toSeconds() + " s) : " + what, last);
    }

    /** Attend une valeur non nulle. */
    static <T> T awaitValue(java.util.function.Supplier<T> value, String what) {
        List<T> found = new ArrayList<>(1);
        await(() -> {
            T v = value.get();
            if (v != null) {
                found.add(v);
            }
            return v != null;
        }, what);
        return found.getFirst();
    }

    /** Vérifie qu'une condition reste fausse pendant {@code during} (ce qui ne doit pas arriver n'arrive pas). */
    static void never(BooleanSupplier condition, String what, Duration during) {
        Instant deadline = Instant.now().plus(during);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                throw new AssertionError("Inattendu : " + what);
            }
            sleep(Duration.ofMillis(200));
        }
    }

    static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        List<Box> all = new ArrayList<>(boxes.values());
        java.util.Collections.reverse(all);
        all.forEach(Box::close);
        stopRelay();
        directory.close();
        deleteTree(dir); // bases, morceaux, examens synthétiques : rien ne reste sur le disque
    }

    private static void deleteTree(Path root) {
        try (var files = Files.walk(root)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // fichier encore ouvert : laissé au nettoyage du dossier temporaire
                }
            });
        } catch (IOException e) {
            // dossier déjà supprimé
        }
    }
}
