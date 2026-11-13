package com.imagunity.directory;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Outil du responsable de l'organisation (et, en attendant son intégration, de l'installateur d'une box).
 *
 * <pre>
 * organisation-creer --organisation &lt;id&gt; --cles &lt;fichier&gt;
 * empreinte          --cle-publique &lt;fichier&gt;
 * box-demande        --cles &lt;fichier&gt; --box-id &lt;uuid&gt; --nom &lt;nom&gt; [--iu &lt;adresse&gt;] --sortie &lt;fichier&gt;
 * enroler            --cles &lt;clé organisation&gt; --demande &lt;fichier&gt; [--validite-jours 365] [--serie 1] --sortie &lt;fichier&gt;
 * revoquer           --cles &lt;clé organisation&gt; --entree &lt;fichier&gt; --motif &lt;texte&gt; --sortie &lt;fichier&gt;
 * verifier           --cle-publique &lt;fichier&gt; --entree &lt;fichier&gt;
 * publier            --annuaire &lt;url&gt; --entree &lt;fichier&gt; [--compte &lt;utilisateur:mot de passe&gt;]
 * proteger           --cles &lt;fichier&gt; [--box]
 * </pre>
 * Les clés privées sont chiffrées par phrase de passe : elle vient de {@code IMAGUNITY_PHRASE_ORGANISATION} (clé du
 * responsable) ou {@code IMAGUNITY_PHRASE_BOX} (clé d'une box), sinon elle est demandée au terminal (deux fois à la
 * création). {@code --sans-phrase} crée une clé en clair (déconseillé).
 * Pour {@code publier}, le compte peut venir de la variable {@code IMAGUNITY_ANNUAIRE_COMPTE} (préférable : un
 * argument de ligne de commande est visible des autres utilisateurs de la machine).
 */
public final class DirectoryAdmin {

    static final String ACCOUNT_VARIABLE = "IMAGUNITY_ANNUAIRE_COMPTE";
    static final String ORGANIZATION_PASSPHRASE = "IMAGUNITY_PHRASE_ORGANISATION";
    static final String BOX_PASSPHRASE = "IMAGUNITY_PHRASE_BOX";

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

    DirectoryAdmin(PrintStream out, PrintStream err, Clock clock, Map<String, String> environment) {
        this(out, err, clock, environment, label -> null);
    }

    DirectoryAdmin(PrintStream out, PrintStream err, Clock clock, Map<String, String> environment, Prompt prompt) {
        this.out = out;
        this.err = err;
        this.clock = clock;
        this.environment = environment;
        this.prompt = prompt;
    }

    public static void main(String[] args) {
        java.io.Console console = System.console();
        Prompt prompt = console == null ? label -> null : label -> console.readPassword("%s : ", label);
        System.exit(new DirectoryAdmin(System.out, System.err, Clock.systemUTC(), System.getenv(), prompt).run(args));
    }

    /** Exécute une commande ; renvoie le code de sortie (0 : succès, 1 : refus ou erreur, 2 : usage). */
    int run(String[] args) {
        if (args.length == 0) {
            return usage("Commande manquante");
        }
        Map<String, String> options;
        try {
            options = options(args);
        } catch (IllegalArgumentException e) {
            return usage(e.getMessage());
        }
        try {
            return switch (args[0]) {
                case "organisation-creer" -> createOrganization(options);
                case "empreinte" -> fingerprint(options);
                case "box-demande" -> boxRequest(options);
                case "enroler" -> enroll(options);
                case "revoquer" -> revoke(options);
                case "verifier" -> verify(options);
                case "publier" -> publish(options);
                case "proteger" -> protect(options);
                case "configuration-signer" -> signConfiguration(options);
                case "configuration-publier" -> publishConfiguration(options);
                default -> usage("Commande inconnue : " + args[0]);
            };
        } catch (MissingOptionException e) {
            return usage(e.getMessage());
        } catch (DirectoryException | IllegalArgumentException | IOException e) {
            err.println("Erreur : " + e.getMessage());
            return 1;
        }
    }

    private int createOrganization(Map<String, String> o) throws IOException {
        String organization = Organization.requireValidId(require(o, "organisation"));
        Path keys = Path.of(require(o, "cles"));
        HybridKeyPair pair = HybridKeyPair.generate();
        char[] passphrase = newPassphrase(o, ORGANIZATION_PASSPHRASE, "Phrase de passe de la clé du responsable");
        KeyFiles.writePrivate(keys, pair, passphrase);
        Path publicFile = Path.of(keys + ".pub.json");
        KeyFiles.writeOrganizationPublic(publicFile, organization, pair.publicKey());
        out.println("Clé du responsable de « " + organization + " » créée : " + keys + " (à garder hors ligne"
                + (passphrase == null ? ", NON protégée" : ", protégée par phrase de passe") + ")");
        out.println("Clé publique, à installer dans les box : " + publicFile);
        out.println("Empreinte à communiquer hors bande : " + pair.publicKey().shortCode());
        return 0;
    }

    /** Fichier des clés de chiffrement d'une box, à côté de sa clé de signature. */
    static Path encryptionFileOf(Path keysFile) {
        return Path.of(keysFile + ".chiffrement");
    }

    private int fingerprint(Map<String, String> o) throws IOException {
        KeyFiles.OrganizationPublicKey key = KeyFiles.readOrganizationPublic(Path.of(require(o, "cle-publique")));
        out.println("Organisation « " + key.organization() + " » : " + key.key().shortCode());
        return 0;
    }

    private int boxRequest(Map<String, String> o) throws IOException {
        Path keysFile = Path.of(require(o, "cles"));
        Path encryptionFile = encryptionFileOf(keysFile);
        HybridKeyPair keys;
        HybridKemKeyPair encryption;
        if (Files.exists(keysFile)) {
            char[] passphrase = null;
            if (KeyFiles.isProtected(keysFile)) {
                passphrase = passphrase(BOX_PASSPHRASE, "Phrase de passe de la clé de la box", false);
                if (passphrase == null) {
                    throw new MissingOptionException("Clé protégée : phrase de passe dans " + BOX_PASSPHRASE
                            + " ou saisie au terminal");
                }
            } else {
                warnClear(keysFile);
            }
            keys = KeyFiles.readPrivate(keysFile, passphrase);
            if (Files.exists(encryptionFile)) {
                encryption = KeyFiles.readEncryptionPrivate(encryptionFile, passphrase);
            } else {
                encryption = HybridKemKeyPair.generate();
                KeyFiles.writeEncryptionPrivate(encryptionFile, encryption, passphrase);
                out.println("Clés de chiffrement de la box créées : " + encryptionFile);
            }
        } else {
            keys = HybridKeyPair.generate();
            encryption = HybridKemKeyPair.generate();
            char[] passphrase = newPassphrase(o, BOX_PASSPHRASE, "Phrase de passe de la clé de la box");
            KeyFiles.writePrivate(keysFile, keys, passphrase);
            KeyFiles.writeEncryptionPrivate(encryptionFile, encryption, passphrase);
            out.println("Identité de la box créée : " + keysFile + " (chiffrement : " + encryptionFile + ")");
        }
        Map<String, String> transport = new LinkedHashMap<>();
        if (o.containsKey("iu")) {
            transport.put("iu", o.get("iu"));
        }
        SignedDocument request = EnrollmentRequest.create(keys, encryption.publicKey(),
                UUID.fromString(require(o, "box-id")),
                require(o, "nom"), transport, clock.instant());
        write(Path.of(require(o, "sortie")), request);
        out.println("Demande d'enrôlement écrite. Empreinte de la box : " + keys.publicKey().shortCode());
        return 0;
    }

    private int enroll(Map<String, String> o) throws IOException {
        Organization organization = organization(o);
        SignedDocument request = read(Path.of(require(o, "demande")));
        EnrollmentRequest r = EnrollmentRequest.verify(request);
        long days = Long.parseLong(o.getOrDefault("validite-jours", "365"));
        long serial = Long.parseLong(o.getOrDefault("serie", "1"));
        SignedDocument entry = organization.enroll(request, clock.instant(), Duration.ofDays(days), serial);
        write(Path.of(require(o, "sortie")), entry);
        out.println("Box « " + r.displayName() + " » (" + r.boxId() + ") enrôlée pour " + days + " jours, n° "
                + serial);
        out.println("Vérifiez hors bande l'empreinte de la box avec son installateur : "
                + HybridPublicKey.decode(r.signingKeys()).shortCode());
        if (r.encryptionKeys() == null) {
            out.println("Attention : demande sans clés de chiffrement ; les box qui exigent le chiffrement refuseront "
                    + "d'envoyer à cette box. Refaire la demande avec une box à jour.");
        }
        return 0;
    }

    private int revoke(Map<String, String> o) throws IOException {
        Organization organization = organization(o);
        SignedDocument revoked = organization.revoke(read(Path.of(require(o, "entree"))), clock.instant(),
                require(o, "motif"));
        write(Path.of(require(o, "sortie")), revoked);
        out.println("Révocation signée : à publier dans l'annuaire");
        return 0;
    }

    private int verify(Map<String, String> o) throws IOException {
        KeyFiles.OrganizationPublicKey key = KeyFiles.readOrganizationPublic(Path.of(require(o, "cle-publique")));
        EntryVerifier verifier = new EntryVerifier(key.organization(), key.key(), SerialMemory.inMemory(), clock);
        EntryVerifier.Verified verified = verifier.verify(read(Path.of(require(o, "entree"))));
        BoxEntry e = verified.entry();
        out.println("Signature valide. Box « " + e.displayName() + " » (" + e.boxId() + "), n° " + e.serial() + " : "
                + verified.status());
        out.println(e.encryptionKeys() == null ? "Pas de clés de chiffrement : entrée à réémettre"
                : "Clés de chiffrement publiées (X25519 + ML-KEM-768)");
        return verified.usable() ? 0 : 1;
    }

    private int publish(Map<String, String> o) throws IOException {
        SignedDocument entry = read(Path.of(require(o, "entree")));
        HttpResponse<String> response = post(o, "/directory/v1/entries", entry);
        switch (response.statusCode()) {
            case 201 -> out.println("Entrée publiée dans l'annuaire");
            case 200 -> out.println("Entrée déjà publiée à l'identique");
            default -> {
                err.println("Publication refusée (HTTP " + response.statusCode() + ") : " + response.body());
                return 1;
            }
        }
        return 0;
    }

    /**
     * Signe un brouillon de configuration (préparé et vérifié par l'interface d'administration) avec la clé du
     * responsable, après en avoir affiché le contenu.
     */
    private int signConfiguration(Map<String, String> o) throws IOException {
        Organization organization = organization(o);
        CentralConfiguration draft = CentralConfiguration.fromJson(Files.readString(
                Path.of(require(o, "brouillon")), StandardCharsets.UTF_8));
        out.println("Configuration de « " + draft.organization() + " », " + draft.describe());
        out.println("Box visées : " + (draft.target().all() ? "toute l'organisation"
                : draft.target().boxes().size() + " box (" + draft.target().boxes() + ")"));
        if (draft.priority() != null) {
            out.println("Priorité : par défaut " + (draft.priority().defaultPriority() == null ? "5"
                    : draft.priority().defaultPriority()) + " ; règles "
                    + draft.priority().rules().stream().map(r -> r.name() + " (" + r.priority() + ")").toList());
        }
        if (draft.routing() != null) {
            out.println("Routage : règles " + draft.routing().rules().stream().map(r -> r.name() + " → " + r.to())
                    .toList());
        }
        SignedDocument signed = organization.signConfiguration(draft);
        write(Path.of(require(o, "sortie")), signed);
        out.println("Configuration signée : à publier (configuration-publier)");
        return 0;
    }

    private int publishConfiguration(Map<String, String> o) throws IOException {
        SignedDocument configuration = read(Path.of(require(o, "configuration")));
        HttpResponse<String> response = post(o, "/directory/v1/configurations", configuration);
        switch (response.statusCode()) {
            case 201 -> out.println("Configuration publiée : les box visées l'appliqueront à leur prochaine lecture");
            case 200 -> out.println("Configuration déjà publiée à l'identique");
            default -> {
                err.println("Publication refusée (HTTP " + response.statusCode() + ") : " + response.body());
                return 1;
            }
        }
        return 0;
    }

    /** Dépôt d'un document signé sur le service d'annuaire, avec le compte du responsable. */
    private HttpResponse<String> post(Map<String, String> o, String path, SignedDocument document)
            throws IOException {
        String directory = require(o, "annuaire").replaceAll("/+$", "");
        String account = o.getOrDefault("compte", environment.get(ACCOUNT_VARIABLE));
        if (account == null || !account.contains(":")) {
            throw new MissingOptionException("Compte de dépôt manquant : --compte utilisateur:mot-de-passe ou "
                    + ACCOUNT_VARIABLE);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(directory + path))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(account.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(document.toJson()))
                .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Publication interrompue", e);
        }
    }

    private Organization organization(Map<String, String> o) throws IOException {
        Path keys = Path.of(require(o, "cles"));
        KeyFiles.OrganizationPublicKey publicKey = KeyFiles.readOrganizationPublic(Path.of(keys + ".pub.json"));
        HybridKeyPair pair = readKeys(keys, ORGANIZATION_PASSPHRASE, "Phrase de passe de la clé du responsable");
        if (!pair.publicKey().fingerprint().equals(publicKey.key().fingerprint())) {
            throw new DirectoryException("La clé privée ne correspond pas à la clé publique de l'organisation");
        }
        return new Organization(publicKey.organization(), pair);
    }

    private int protect(Map<String, String> o) throws IOException {
        Path keys = Path.of(require(o, "cles"));
        boolean box = o.containsKey("box");
        char[] passphrase = passphrase(box ? BOX_PASSPHRASE : ORGANIZATION_PASSPHRASE,
                "Nouvelle phrase de passe", true);
        if (passphrase == null) {
            throw new MissingOptionException("Phrase de passe manquante : " + (box ? BOX_PASSPHRASE
                    : ORGANIZATION_PASSPHRASE) + " ou saisie au terminal");
        }
        KeyFiles.protect(keys, passphrase);
        out.println("Clé protégée par phrase de passe : " + keys);
        Path encryption = encryptionFileOf(keys);
        if (box && Files.exists(encryption) && !KeyFiles.isProtected(encryption)) {
            KeyFiles.protect(encryption, passphrase);
            out.println("Clés de chiffrement protégées par la même phrase de passe : " + encryption);
        }
        return 0;
    }

    private void warnClear(Path file) {
        err.println("Attention : clé non protégée par phrase de passe (" + file + ") ; la protéger avec « proteger »");
    }

    /** Lit une clé privée ; demande sa phrase de passe si elle est protégée, avertit sinon. */
    private HybridKeyPair readKeys(Path file, String variable, String label) throws IOException {
        if (!KeyFiles.isProtected(file)) {
            warnClear(file);
            return KeyFiles.readPrivate(file);
        }
        char[] passphrase = passphrase(variable, label, false);
        if (passphrase == null) {
            throw new MissingOptionException("Clé protégée : phrase de passe dans " + variable + " ou saisie au terminal");
        }
        return KeyFiles.readPrivate(file, passphrase);
    }

    /** Phrase de passe d'une nouvelle clé ; {@code null} seulement avec {@code --sans-phrase}. */
    private char[] newPassphrase(Map<String, String> o, String variable, String label) {
        if (o.containsKey("sans-phrase")) {
            err.println("Attention : clé créée sans phrase de passe (déconseillé)");
            return null;
        }
        char[] passphrase = passphrase(variable, label, true);
        if (passphrase == null) {
            throw new MissingOptionException("Phrase de passe requise : " + variable
                    + ", saisie au terminal, ou --sans-phrase (déconseillé)");
        }
        KeyProtection.requireStrong(passphrase);
        return passphrase;
    }

    /** Phrase de passe : variable d'environnement, sinon terminal (confirmée si {@code confirm}). */
    private char[] passphrase(String variable, String label, boolean confirm) {
        String fromEnvironment = environment.get(variable);
        if (fromEnvironment != null && !fromEnvironment.isEmpty()) {
            return fromEnvironment.toCharArray();
        }
        char[] first = prompt.ask(label);
        if (first == null || !confirm) {
            return first;
        }
        char[] second = prompt.ask(label + " (confirmation)");
        if (second == null || !java.util.Arrays.equals(first, second)) {
            throw new IllegalArgumentException("Les deux saisies de la phrase de passe diffèrent");
        }
        return first;
    }

    private static SignedDocument read(Path file) throws IOException {
        return SignedDocument.fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static void write(Path file, SignedDocument document) throws IOException {
        Files.writeString(file, document.toJson(), StandardCharsets.UTF_8);
    }

    private int usage(String problem) {
        err.println(problem);
        err.println("""
                Usage :
                  organisation-creer --organisation <id> --cles <fichier>
                  empreinte          --cle-publique <fichier>
                  box-demande        --cles <fichier> --box-id <uuid> --nom <nom> [--iu <adresse>] --sortie <fichier>
                  enroler            --cles <clé organisation> --demande <fichier> [--validite-jours 365] [--serie 1] --sortie <fichier>
                  revoquer           --cles <clé organisation> --entree <fichier> --motif <texte> --sortie <fichier>
                  verifier           --cle-publique <fichier> --entree <fichier>
                  publier            --annuaire <url> --entree <fichier> [--compte <utilisateur:mot de passe>]
                  proteger           --cles <fichier> [--box]
                  configuration-signer  --cles <clé organisation> --brouillon <fichier> --sortie <fichier>
                  configuration-publier --annuaire <url> --configuration <fichier> [--compte <utilisateur:mot de passe>]
                Phrases de passe : IMAGUNITY_PHRASE_ORGANISATION, IMAGUNITY_PHRASE_BOX, ou saisie au terminal ;
                --sans-phrase à la création d'une clé (déconseillé).""");
        return 2;
    }

    /** Options {@code --nom valeur} et drapeaux sans valeur ({@code --sans-phrase}, {@code --box}). */
    private static final java.util.Set<String> FLAGS = java.util.Set.of("sans-phrase", "box");

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("Option mal formée : " + args[i]);
            }
            String name = args[i].substring(2);
            if (FLAGS.contains(name)) {
                options.put(name, "oui");
                continue;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("Option mal formée : " + args[i]);
            }
            options.put(name, args[++i]);
        }
        return options;
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new MissingOptionException("Option manquante : --" + name);
        }
        return value;
    }

    private static final class MissingOptionException extends RuntimeException {
        MissingOptionException(String message) {
            super(message);
        }
    }
}
