package com.imagunity.release;

import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.KeyProtection;
import com.imagunity.directory.SignedDocument;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mises à jour signées : outil de l'éditeur, et lanceur de la box.
 *
 * <pre>
 * Éditeur (poste hors ligne qui garde la clé) :
 *   cle-creer  --editeur &lt;nom&gt; --cles &lt;fichier&gt; [--sans-phrase]
 *   empreinte  --cle-publique &lt;fichier&gt;
 *   signer     --cles &lt;fichier&gt; --artefact &lt;jar&gt; --version &lt;v&gt; --sequence &lt;n&gt; [--sortie &lt;manifeste&gt;]
 *   verifier   --cle-publique &lt;fichier&gt; --manifeste &lt;fichier&gt; --artefact &lt;jar&gt;
 *
 * Box (au démarrage, avant le logiciel) :
 *   choisir    écrit sur la sortie standard « jar TAB version TAB n° » de la version à exécuter
 *
 * Box (mise à jour automatique, logiciel en marche) :
 *   telecharger  installe une version plus récente (dépôt, serveur) sans la démarrer ; écrit « nouvelle TAB n° »
 *                si une version plus récente que celle en marche est prête (redémarrer la box), sinon « a-jour TAB n° »
 * </pre>
 *
 * La phrase de passe de la clé de l'éditeur vient de {@code IMAGUNITY_PHRASE_EDITEUR}, sinon elle est demandée au
 * terminal (deux fois à la création).
 *
 * <p>{@code choisir} lit sa configuration dans l'environnement :
 * <ul>
 *   <li>{@code IMAGUNITY_RELEASE_KEY} : clé publique de l'éditeur ; sans elle, la vérification est désactivée et la
 *       version livrée avec l'image démarre telle quelle, avec un avertissement ;</li>
 *   <li>{@code IMAGUNITY_RELEASE_CODE} : son empreinte courte, vérifiée hors bande (sinon, refus de démarrer) ;</li>
 *   <li>{@code IMAGUNITY_RELEASE_DIR} (défaut {@code /data/mises-a-jour}) : versions installées et dépôt manuel ;</li>
 *   <li>{@code IMAGUNITY_RELEASE_BUNDLED} (défaut {@code /app/imagunity-box.jar}) : version livrée avec l'image ;</li>
 *   <li>{@code IMAGUNITY_UPDATE_URL} (facultatif) : serveur de mises à jour.</li>
 * </ul>
 */
public final class ReleaseTool {

    static final String PASSPHRASE = "IMAGUNITY_PHRASE_EDITEUR";
    private static final Set<String> FLAGS = Set.of("sans-phrase");

    /** Saisie masquée au terminal ; {@code null} si aucun terminal. */
    @FunctionalInterface
    interface Prompt {
        char[] ask(String label);
    }

    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;
    private final Map<String, String> environment;
    private final Prompt prompt;
    private final HttpClient http;

    ReleaseTool(PrintStream out, PrintStream err, Clock clock, Map<String, String> environment, Prompt prompt,
                HttpClient http) {
        this.out = out;
        this.err = err;
        this.clock = clock;
        this.environment = environment;
        this.prompt = prompt;
        this.http = http;
    }

    public static void main(String[] args) {
        java.io.Console console = System.console();
        Prompt prompt = console == null ? label -> null : label -> console.readPassword("%s : ", label);
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        System.exit(new ReleaseTool(System.out, System.err, Clock.systemUTC(), System.getenv(), prompt, http)
                .run(args));
    }

    /** Exécute une commande ; renvoie le code de sortie (0 : succès, 1 : refus ou erreur, 2 : usage). */
    int run(String[] args) {
        if (args.length == 0) {
            return usage("Commande manquante");
        }
        Map<String, String> o;
        try {
            o = options(args);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        try {
            return switch (args[0]) {
                case "cle-creer" -> createKey(o);
                case "empreinte" -> fingerprint(o);
                case "signer" -> sign(o);
                case "verifier" -> verify(o);
                case "choisir" -> choose();
                case "telecharger" -> download();
                default -> usage("Commande inconnue : " + args[0]);
            };
        } catch (MissingOptionException e) {
            return usage(e.getMessage());
        } catch (ReleaseException | DirectoryException | IllegalArgumentException | IOException e) {
            err.println("Erreur : " + e.getMessage());
            return 1;
        }
    }

    // --- Éditeur ---

    private int createKey(Map<String, String> o) throws IOException {
        String publisher = require(o, "editeur");
        Path keys = Path.of(require(o, "cles"));
        char[] passphrase;
        if (o.containsKey("sans-phrase")) {
            err.println("Attention : clé créée sans phrase de passe (déconseillé)");
            passphrase = null;
        } else {
            passphrase = passphrase(true);
            if (passphrase == null) {
                throw new MissingOptionException("Phrase de passe requise : " + PASSPHRASE
                        + ", saisie au terminal, ou --sans-phrase (déconseillé)");
            }
            KeyProtection.requireStrong(passphrase);
        }
        HybridKeyPair pair = HybridKeyPair.generate();
        KeyFiles.writePrivate(keys, pair, passphrase);
        Path publicFile = Path.of(keys + ".pub.json");
        PublisherKey.write(publicFile, publisher, pair.publicKey());
        out.println("Clé de signature des versions de « " + publisher + " » créée : " + keys
                + " (à garder hors ligne)");
        out.println("Clé publique, à installer dans les box : " + publicFile);
        out.println("Empreinte à communiquer hors bande : " + pair.publicKey().shortCode());
        return 0;
    }

    private int fingerprint(Map<String, String> o) throws IOException {
        PublisherKey key = PublisherKey.read(Path.of(require(o, "cle-publique")));
        out.println("Éditeur « " + key.publisher() + " » : " + key.key().shortCode());
        return 0;
    }

    private int sign(Map<String, String> o) throws IOException {
        Path keysFile = Path.of(require(o, "cles"));
        Path artifact = Path.of(require(o, "artefact"));
        long sequence = Long.parseLong(require(o, "sequence"));
        char[] passphrase = null;
        if (KeyFiles.isProtected(keysFile)) {
            passphrase = passphrase(false);
            if (passphrase == null) {
                throw new MissingOptionException("Clé protégée : phrase de passe dans " + PASSPHRASE
                        + " ou saisie au terminal");
            }
        } else {
            err.println("Attention : clé non protégée par phrase de passe (" + keysFile + ")");
        }
        HybridKeyPair keys = KeyFiles.readPrivate(keysFile, passphrase);
        ReleaseManifest manifest = new ReleaseManifest(com.imagunity.directory.Suite.ID,
                o.getOrDefault("produit", ReleaseManifest.BOX), require(o, "version"), sequence,
                new ReleaseManifest.Artifact(artifact.getFileName().toString(), Files.size(artifact),
                        ReleaseVerifier.sha256(artifact)), clock.instant());
        Path output = Path.of(o.getOrDefault("sortie", artifact + ReleaseStore.MANIFEST_SUFFIX));
        Files.writeString(output, manifest.sign(keys).toJson(), StandardCharsets.UTF_8);
        out.println("Version " + manifest.label() + " signée : " + output);
        return 0;
    }

    private int verify(Map<String, String> o) throws IOException {
        PublisherKey key = PublisherKey.read(Path.of(require(o, "cle-publique")));
        SignedDocument document = SignedDocument.fromJson(Files.readString(Path.of(require(o, "manifeste")),
                StandardCharsets.UTF_8));
        ReleaseManifest manifest = new ReleaseVerifier(key.key(), o.getOrDefault("produit", ReleaseManifest.BOX))
                .verify(document, Path.of(require(o, "artefact")));
        out.println("Signature valide : version " + manifest.label() + " de « " + key.publisher() + " »");
        return 0;
    }

    // --- Box ---

    private int choose() throws IOException {
        Path bundled = Path.of(environment.getOrDefault("IMAGUNITY_RELEASE_BUNDLED", "/app/imagunity-box.jar"));
        String keyFile = environment.get("IMAGUNITY_RELEASE_KEY");
        if (keyFile == null || keyFile.isBlank()) {
            err.println("Attention : vérification des mises à jour DÉSACTIVÉE (IMAGUNITY_RELEASE_KEY absent) : "
                    + "démarrage sur la version livrée avec l'image, sans vérification");
            out.println(bundled + "\tnon-verifiee\t0");
            return 0;
        }
        ReleaseStore store = store(keyFile);
        if (store == null) {
            return 1;
        }
        ReleaseStore.Choice choice = store.choose(bundled);
        out.println(choice.jar() + "\t" + choice.manifest().version() + "\t" + choice.manifest().sequence());
        return 0;
    }

    /**
     * Mise à jour automatique, la box en marche : importe le dépôt et interroge le serveur, sans rien démarrer ni
     * changer la version retenue comme exécutée. Le planificateur redémarre la box si une version plus récente est
     * prête ; c'est {@code choisir}, au démarrage, qui la revérifie et la retient.
     */
    private int download() throws IOException {
        String keyFile = environment.get("IMAGUNITY_RELEASE_KEY");
        if (keyFile == null || keyFile.isBlank()) {
            err.println("Erreur : vérification des mises à jour désactivée (IMAGUNITY_RELEASE_KEY absent) : "
                    + "pas de mise à jour automatique");
            return 1;
        }
        ReleaseStore store = store(keyFile);
        if (store == null) {
            return 1;
        }
        long running = store.installed().map(ReleaseStore.Installed::sequence).orElse(0L);
        long ready = store.highestInstalled();
        out.println(ready > running ? "nouvelle\t" + ready : "a-jour\t" + running);
        return 0;
    }

    /** Dépôt des versions, après vérification de l'empreinte de l'éditeur ; {@code null} si elle ne correspond pas. */
    private ReleaseStore store(String keyFile) throws IOException {
        PublisherKey key = PublisherKey.read(Path.of(keyFile));
        String actual = key.key().shortCode();
        if (!normalize(actual).equals(normalize(environment.get("IMAGUNITY_RELEASE_CODE")))) {
            err.println("Erreur : empreinte de l'éditeur non confirmée : la clé installée a l'empreinte " + actual
                    + " ; après l'avoir vérifiée hors bande, la recopier dans IMAGUNITY_RELEASE_CODE");
            return null;
        }
        ReleaseStore store = new ReleaseStore(Path.of(environment.getOrDefault("IMAGUNITY_RELEASE_DIR",
                "/data/mises-a-jour")), new ReleaseVerifier(key.key(), ReleaseManifest.BOX), clock,
                message -> err.println("[mises à jour] " + message));
        store.importDepot();
        String url = environment.get("IMAGUNITY_UPDATE_URL");
        if (url != null && !url.isBlank()) {
            store.fetch(URI.create(url), http, store.installed().map(ReleaseStore.Installed::sequence).orElse(0L));
        }
        return store;
    }

    static String normalize(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    // --- Outils ---

    private char[] passphrase(boolean confirm) {
        String fromEnvironment = environment.get(PASSPHRASE);
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return fromEnvironment.toCharArray();
        }
        char[] first = prompt.ask("Phrase de passe de la clé de l'éditeur");
        if (first == null || !confirm) {
            return first;
        }
        char[] second = prompt.ask("Phrase de passe de la clé de l'éditeur (confirmation)");
        if (second == null || !Arrays.equals(first, second)) {
            throw new IllegalArgumentException("Les deux saisies de la phrase de passe diffèrent");
        }
        return first;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Argument inattendu : " + args[i]);
            }
            String name = args[i].substring(2);
            if (FLAGS.contains(name)) {
                options.put(name, "");
            } else if (i + 1 < args.length) {
                options.put(name, args[++i]);
            } else {
                throw new IllegalArgumentException("Valeur manquante pour --" + name);
            }
        }
        return options;
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new MissingOptionException("Option manquante : --" + name);
        }
        return value;
    }

    private int usage(String problem) {
        err.println(problem);
        err.println("""
                Usage :
                  cle-creer  --editeur <nom> --cles <fichier> [--sans-phrase]
                  empreinte  --cle-publique <fichier>
                  signer     --cles <fichier> --artefact <jar> --version <v> --sequence <n> [--sortie <manifeste>]
                  verifier   --cle-publique <fichier> --manifeste <fichier> --artefact <jar>
                  choisir    (box : configuration par IMAGUNITY_RELEASE_*)
                  telecharger (box en marche : installe une version plus récente, sans la démarrer)""");
        return 2;
    }

    /** Option obligatoire absente. */
    private static final class MissingOptionException extends IllegalArgumentException {

        MissingOptionException(String message) {
            super(message);
        }
    }
}
