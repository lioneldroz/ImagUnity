package com.imagunity.directory;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Outil du responsable de l'organisation (et, en attendant son intégration, de l'installateur d'une box).
 *
 * <pre>
 * organisation-creer --organisation &lt;id&gt; --cles &lt;fichier&gt;
 * empreinte          --cle-publique &lt;fichier&gt;
 * box-demande        --cles &lt;fichier&gt; --box-id &lt;uuid&gt; --nom &lt;nom&gt; [--iu &lt;adresse&gt;] --sortie &lt;fichier&gt;
 * enroler            --cles &lt;clé organisation&gt; --demande &lt;fichier&gt; [--validite-jours 90] [--serie 1] --sortie &lt;fichier&gt;
 * revoquer           --cles &lt;clé organisation&gt; --entree &lt;fichier&gt; --motif &lt;texte&gt; --sortie &lt;fichier&gt;
 * verifier           --cle-publique &lt;fichier&gt; --entree &lt;fichier&gt;
 * publier            --annuaire &lt;url&gt; --entree &lt;fichier&gt; [--compte &lt;utilisateur:mot de passe&gt;]
 * proteger           --cles &lt;fichier&gt; [--box]
 * interface          --cles &lt;clé organisation&gt; --codes &lt;fichier&gt; --annuaire &lt;url&gt; [--port 0] [--ouvrir]
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

        /** Réponse visible (confirmation) ; par défaut comme {@link #ask}. */
        default String answer(String label) {
            char[] answer = ask(label);
            return answer == null ? null : new String(answer);
        }
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
        Prompt prompt = console == null ? label -> null : new Prompt() {
            @Override
            public char[] ask(String label) {
                return console.readPassword("%s : ", label);
            }

            @Override
            public String answer(String label) {
                return console.readLine("%s : ", label);
            }
        };
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
                case "code-creer" -> createCode(options);
                case "enroler-attente" -> enrollPending(options);
                case "renouveler" -> renewAll(options);
                case "interface" -> localInterface(options);
                case "reconnaitre" -> recognize(options);
                case "reconnaissances" -> listRecognitions(options);
                case "reconnaissance-revoquer" -> revokeRecognition(options);
                case "fraicheur-deleguer" -> delegateFreshness(options);
                case "fraicheur-verifier" -> checkFreshness(options);
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
        long days = Long.parseLong(o.getOrDefault("validite-jours", "90"));
        long serial = Long.parseLong(o.getOrDefault("serie", "1"));
        java.util.List<String> groups = groupsOption(o);
        SignedDocument entry = organization.enroll(request, clock.instant(), Duration.ofDays(days), serial, groups);
        write(Path.of(require(o, "sortie")), entry);
        out.println("Box « " + r.displayName() + " » (" + r.boxId() + ") enrôlée pour " + days + " jours, n° "
                + serial + (groups.isEmpty() ? "" : ", groupes " + BoxEntry.groups(groups)));
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
        out.println("Box visées : " + draft.target().describe());
        if (draft.priority() != null) {
            out.println("Priorité : par défaut " + (draft.priority().defaultPriority() == null ? "5"
                    : draft.priority().defaultPriority()) + " ; règles "
                    + draft.priority().rules().stream().map(r -> r.name() + " (" + r.priority() + ")").toList());
        }
        if (draft.routing() != null) {
            out.println("Routage : règles " + draft.routing().rules().stream().map(r -> r.name() + " → " + r.to())
                    .toList());
        }
        // Le brouillon vient de l'interface d'administration, qui peut être compromise : le responsable relit le
        // document exact qu'il va signer, pas seulement son résumé.
        out.println("Document exact à signer :");
        out.println(tools.jackson.databind.json.JsonMapper.builder().build().readTree(draft.toJson())
                .toPrettyString());
        if (!o.containsKey("confirmer")) {
            String answer = prompt.answer("Signer ce document ? Tapez « oui » pour confirmer");
            if (answer == null || !answer.strip().equalsIgnoreCase("oui")) {
                err.println("Signature abandonnée");
                return 1;
            }
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

    // --- Enrôlement automatique ---

    /** Tire un code d'enrôlement pour une box à venir, et le garde dans le fichier des codes du responsable. */
    private int createCode(Map<String, String> o) throws IOException {
        Path file = Path.of(require(o, "codes"));
        String name = require(o, "nom");
        java.util.List<String> groups = BoxEntry.groups(groupsOption(o));
        EnrollmentCodes.Entry entry = EnrollmentCodes.create(name, groups,
                Long.parseLong(o.getOrDefault("validite-jours", "30")), clock.instant());
        EnrollmentCodes.read(file).with(entry).write(file);
        out.println("Code d'enrôlement de la box « " + entry.name() + " »" + (groups.isEmpty() ? "" : ", groupes "
                + groups) + ", valable jusqu'au " + day(entry.expiresAt()) + " : " + entry.code());
        out.println("À donner à l'installateur de la box, qui le recopie dans sa configuration :");
        out.println("  IMAGUNITY_DIRECTORY_ENROLLMENT_CODE=" + entry.code());
        return 0;
    }

    /**
     * Enrôle les box dont la demande attend dans l'annuaire et porte la preuve d'un code d'enrôlement de ce
     * responsable : signe leur entrée (nom et groupes du code), la publie, et marque le code comme utilisé.
     */
    private int enrollPending(Map<String, String> o) throws IOException {
        Organization organization = organization(o);
        Path file = Path.of(require(o, "codes"));
        EnrollmentCodes codes = EnrollmentCodes.read(file);
        long days = Long.parseLong(o.getOrDefault("validite-jours", "90"));
        HttpResponse<String> response = get(o, "/directory/v1/enrollment-requests", true);
        if (response.statusCode() != 200) {
            err.println("Lecture des demandes refusée (HTTP " + response.statusCode() + ") : " + response.body());
            return 1;
        }
        tools.jackson.databind.JsonNode pending = Json.MAPPER.readTree(response.body());
        int enrolled = 0;
        int skipped = 0;
        for (tools.jackson.databind.JsonNode item : pending) {
            Optional<PendingRequest> assessed = PendingRequest.assess(item, codes, clock.instant());
            if (assessed.isEmpty() || assessed.get().status() == PendingRequest.Status.ILLISIBLE) {
                err.println("Demande illisible ou mal signée : laissée de côté"
                        + assessed.map(a -> " (" + a.detail() + ")").orElse(""));
                skipped++;
                continue;
            }
            PendingRequest p = assessed.get();
            if (p.status() != PendingRequest.Status.CODE_VERIFIE) {
                out.println(p.label() + " : " + p.detail() + (p.status() == PendingRequest.Status.SANS_CODE
                        ? " (enroler, ou l'interface du responsable)" : ""));
                skipped++;
                continue;
            }
            EnrollmentRequest request = p.request();
            EnrollmentSubmission submission = p.submission();
            EnrollmentCodes.Entry c = p.code();
            long serial = calls(o).nextSerial(request.boxId());
            out.println("Box « " + c.name() + " »" + (c.name().equals(request.displayName()) ? ""
                    : " (nom proposé par la box : « " + request.displayName() + " »)") + ", " + request.boxId());
            out.println("  empreinte " + p.fingerprint() + ", code d'enrôlement "
                    + "vérifié, groupes " + (c.groups().isEmpty() ? "aucun" : c.groups()) + ", n° " + serial + ", "
                    + days + " jours");
            if (!confirmed(o, "Enrôler cette box ? Tapez « oui » pour confirmer")) {
                out.println("  Laissée en attente");
                skipped++;
                continue;
            }
            SignedDocument entry = organization.enroll(submission.request(), clock.instant(), Duration.ofDays(days),
                    serial, c.groups(), c.name());
            HttpResponse<String> published = post(o, "/directory/v1/entries", entry);
            if (published.statusCode() != 201 && published.statusCode() != 200) {
                err.println("  Publication refusée (HTTP " + published.statusCode() + ") : " + published.body());
                skipped++;
                continue;
            }
            codes = codes.replace(c, c.used(request.boxId(), clock.instant()));
            codes.write(file);
            out.println("  Enrôlée et publiée");
            enrolled++;
        }
        out.println(enrolled + " box enrôlée(s), " + skipped + " demande(s) laissée(s) de côté");
        return 0;
    }

    /**
     * Interface locale : page servie sur 127.0.0.1 qui liste les demandes en attente et les fait valider (signature
     * sur ce poste, publication) ou refuser ; s'arrête depuis la page ou par Ctrl+C.
     */
    private int localInterface(Map<String, String> o) throws IOException {
        Path keys = Path.of(require(o, "cles"));
        Path codes = Path.of(require(o, "codes"));
        DirectoryCalls calls = calls(o);
        account(o);
        long days = Long.parseLong(o.getOrDefault("validite-jours", "90"));
        int port = Integer.parseInt(o.getOrDefault("port", "0"));
        if (days < 1 || days > 3650 || port < 0 || port > 65535) {
            throw new IllegalArgumentException("Validité (1 à 3650 jours) ou port (0 à 65535) hors limites");
        }
        String phrase = environment.get(ORGANIZATION_PASSPHRASE);
        try (LocalInterface ui = LocalInterface.start(keys, codes, calls, days, port, clock, out,
                phrase == null || phrase.isEmpty() ? null : phrase.toCharArray())) {
            out.println("Interface du responsable : " + ui.url());
            out.println("À ouvrir dans le navigateur de ce poste (adresse personnelle, ne pas la partager) ; "
                    + "arrêt par le bouton « Quitter » ou Ctrl+C.");
            if (o.containsKey("ouvrir")) {
                browse(ui.url());
            }
            ui.awaitClose();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        out.println("Interface arrêtée");
        return 0;
    }

    private void browse(URI url) {
        try {
            java.awt.Desktop.getDesktop().browse(url);
        } catch (IOException | RuntimeException e) {
            err.println("Navigateur non ouvert (" + e.getClass().getSimpleName() + ") : ouvrir l'adresse à la main");
        }
    }

    // --- Fédération ---

    /** Reconnaissance publiée, vérifiée, avec son document signé. */
    record Published(Recognition recognition, SignedDocument document) {
    }

    /** Reconnaissances courantes publiées sur l'annuaire, vérifiées avec la clé publique de l'organisation. */
    private java.util.List<Published> recognitions(Map<String, String> o) throws IOException {
        KeyFiles.OrganizationPublicKey own = KeyFiles.readOrganizationPublic(Path.of(require(o, "cles") + ".pub.json"));
        HttpResponse<String> response = calls(o).read("/directory/v1/recognitions");
        if (response.statusCode() != 200) {
            throw new DirectoryException("Lecture des reconnaissances refusée (HTTP " + response.statusCode() + ")");
        }
        java.util.List<Published> list = new java.util.ArrayList<>();
        for (tools.jackson.databind.JsonNode item : Json.MAPPER.readTree(response.body()).path("recognitions")) {
            SignedDocument document = SignedDocument.fromJson(item.toString());
            try {
                list.add(new Published(new RecognitionVerifier(own.organization(), own.key(), SerialMemory.inMemory())
                        .verify(document), document));
            } catch (DirectoryException e) {
                err.println("Reconnaissance écartée : " + e.getMessage());
            }
        }
        return list;
    }

    /**
     * Reconnaît une organisation partenaire, ou réémet sa reconnaissance (périmètre, relais, validité) : signe après
     * confirmation de l'empreinte du partenaire, vérifiée hors bande, puis publie.
     */
    private int recognize(Map<String, String> o) throws IOException {
        KeyFiles.OrganizationPublicKey key = KeyFiles.readOrganizationPublic(Path.of(require(o, "partenaire")));
        Recognition.Partner partner = new Recognition.Partner(key.organization(), key.signingKeys(),
                require(o, "annuaire-partenaire"), java.util.Arrays.stream(require(o, "relais").split(","))
                .map(String::strip).filter(r -> !r.isEmpty()).toList());
        Recognition.Scope scope = new Recognition.Scope(scopeOption(o, "groupes-locaux"),
                scopeOption(o, "groupes-partenaire"));
        long days = Long.parseLong(o.getOrDefault("validite-jours", "90"));
        if (days < 1 || days > 365) {
            throw new IllegalArgumentException("Validité de la reconnaissance : de 1 à 365 jours");
        }
        Optional<Published> current = recognitions(o).stream()
                .filter(p -> p.recognition().partner().fingerprint().equals(partner.fingerprint())).findFirst();
        out.println("Organisation partenaire « " + partner.organization() + " », empreinte " + partner.shortCode());
        out.println("  relais autorisés : " + String.join(", ", partner.relays()));
        out.println("  groupes locaux : " + describe(scope.localGroups()) + " ; groupes du partenaire : "
                + describe(scope.partnerGroups()) + (scope.empty() ? " (aucun échange possible)" : ""));
        out.println("  " + (current.isEmpty() ? "nouvelle reconnaissance" : "réémission (n° "
                + (current.get().recognition().serial() + 1) + ")") + ", valable " + days + " jours");
        if (!confirmed(o, "Empreinte vérifiée avec le responsable partenaire, hors bande ? Tapez « oui »")) {
            err.println("Reconnaissance abandonnée");
            return 1;
        }
        Organization organization = organization(o);
        SignedDocument document = current.isEmpty()
                ? organization.recognize(partner, scope, clock.instant(), Duration.ofDays(days))
                : organization.renewRecognition(current.get().document(), partner, scope, clock.instant(),
                Duration.ofDays(days));
        HttpResponse<String> published = post(o, "/directory/v1/recognitions", document);
        if (published.statusCode() != 201 && published.statusCode() != 200) {
            err.println("Publication refusée (HTTP " + published.statusCode() + ") : " + published.body());
            return 1;
        }
        out.println("Reconnaissance signée et publiée. L'échange ne marchera que lorsque « " + partner.organization()
                + " » aura reconnu cette organisation à son tour.");
        return 0;
    }

    private int listRecognitions(Map<String, String> o) throws IOException {
        java.util.List<Published> list = recognitions(o);
        if (list.isEmpty()) {
            out.println("Aucune organisation partenaire reconnue");
        }
        for (Published p : list) {
            Recognition r = p.recognition();
            out.println("« " + r.partner().organization() + " », empreinte " + r.partner().shortCode() + " : "
                    + switch (r.statusAt(clock.instant())) {
                        case ACTIVE -> "active jusqu'au " + day(r.validUntil());
                        case EXPIRED -> "expirée le " + day(r.validUntil());
                        case REVOKED -> "révoquée le " + day(r.revokedAt()) + " (" + r.revocationReason() + ")";
                        case NOT_YET_VALID -> "valable à partir du " + day(r.validFrom());
                    } + ", n° " + r.serial());
            out.println("  groupes locaux : " + describe(r.scope().localGroups()) + " ; groupes du partenaire : "
                    + describe(r.scope().partnerGroups()) + " ; relais : " + String.join(", ", r.partner().relays()));
        }
        return 0;
    }

    private int revokeRecognition(Map<String, String> o) throws IOException {
        String wanted = require(o, "empreinte").strip();
        String reason = require(o, "motif");
        Published p = recognitions(o).stream().filter(r -> r.recognition().partner().fingerprint().equals(wanted)
                        || r.recognition().partner().shortCode().equalsIgnoreCase(wanted)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Aucune reconnaissance de l'organisation d'empreinte "
                        + wanted));
        if (!confirmed(o, "Révoquer la reconnaissance de « " + p.recognition().partner().organization()
                + " » ? Les échanges avec ses box cesseront. Tapez « oui »")) {
            err.println("Révocation abandonnée");
            return 1;
        }
        HttpResponse<String> published = post(o, "/directory/v1/recognitions", organization(o).revokeRecognition(
                p.document(), clock.instant(), reason));
        if (published.statusCode() != 201 && published.statusCode() != 200) {
            err.println("Publication refusée (HTTP " + published.statusCode() + ") : " + published.body());
            return 1;
        }
        out.println("Reconnaissance de « " + p.recognition().partner().organization() + " » révoquée");
        return 0;
    }

    // --- Fraîcheur des annuaires ---

    /**
     * Délègue la signature des preuves de fraîcheur à une nouvelle clé, à installer sur le service d'annuaire avec sa
     * délégation. Numéro suivant si une délégation existe déjà dans le fichier de sortie.
     */
    private int delegateFreshness(Map<String, String> o) throws IOException {
        Path keyFile = Path.of(require(o, "sortie-cle"));
        Path delegationFile = Path.of(require(o, "sortie-delegation"));
        long days = Long.parseLong(o.getOrDefault("validite-jours", "30"));
        if (days < 1 || days > 90) {
            throw new IllegalArgumentException("Validité de la délégation : de 1 à 90 jours");
        }
        long serial = 1;
        if (Files.exists(delegationFile)) {
            serial = Json.read(read(delegationFile).payload(), FreshnessDelegation.class, "Délégation de fraîcheur")
                    .serial() + 1;
        }
        Organization organization = organization(o);
        HybridKeyPair key = HybridKeyPair.generate();
        SignedDocument delegation = organization.delegateFreshness(key.publicKey(), clock.instant(),
                Duration.ofDays(days), serial);
        Files.deleteIfExists(keyFile);
        KeyFiles.writePrivate(keyFile, key, null);
        KeyFiles.replace(delegationFile, delegation.toJson());
        out.println("Clé de fraîcheur n° " + serial + " déléguée jusqu'au " + day(clock.instant().plus(Duration.ofDays(
                days))) + " : " + keyFile + " et " + delegationFile);
        out.println("À installer sur le service d'annuaire (IMAGUNITY_DIRECTORY_FRESHNESS_KEY et "
                + "IMAGUNITY_DIRECTORY_FRESHNESS_DELEGATION), puis à renouveler avant la fin de la délégation.");
        return 0;
    }

    /**
     * Vérifie la preuve de fraîcheur publiée par l'annuaire : signatures, âge, et accord avec les entrées et
     * reconnaissances publiées (un annuaire qui retiendrait une révocation se verrait ici).
     */
    private int checkFreshness(Map<String, String> o) throws IOException {
        KeyFiles.OrganizationPublicKey own = KeyFiles.readOrganizationPublic(Path.of(require(o, "cles") + ".pub.json"));
        String known = o.getOrDefault("compte", environment.get(ACCOUNT_VARIABLE));
        DirectoryClient client = new DirectoryClient(URI.create(require(o, "annuaire")), Duration.ofSeconds(20))
                .account(known != null && known.contains(":") ? known : null);
        Optional<Freshness> served = client.fetchFreshness();
        if (served.isEmpty()) {
            err.println("L'annuaire ne publie pas de preuve de fraîcheur (clé de fraîcheur non installée)");
            return 1;
        }
        FreshnessProof proof = served.get().verify(own.organization(), own.key(), clock.instant());
        FreshnessDelegation delegation = served.get().delegationContent();
        Duration age = Duration.between(proof.issuedAt(), clock.instant());
        out.println("Preuve de fraîcheur valide, signée il y a " + Math.max(0, age.toMinutes()) + " min par la clé "
                + "déléguée n° " + delegation.serial() + " (jusqu'au " + day(delegation.validUntil()) + ") : "
                + proof.entries().size() + " box, " + proof.recognitions().size() + " reconnaissance(s)");
        int problems = 0;
        if (Duration.between(clock.instant(), delegation.validUntil()).compareTo(Duration.ofDays(7)) < 0) {
            out.println("Attention : délégation proche de sa fin, à renouveler (fraicheur-deleguer)");
            problems++;
        }
        EntryVerifier verifier = new EntryVerifier(own.organization(), own.key(), SerialMemory.inMemory(), clock);
        for (SignedDocument document : client.fetch()) {
            BoxEntry e;
            try {
                e = verifier.verify(document).entry();
            } catch (DirectoryException ex) {
                continue;
            }
            Long announced = proof.entries().get(e.boxId());
            if (announced == null || announced != e.serial()) {
                out.println("Écart : box « " + e.displayName() + " » publiée au n° " + e.serial() + ", preuve au n° "
                        + announced);
                problems++;
            }
        }
        for (Published p : recognitions(o)) {
            Long announced = proof.recognitions().get(p.recognition().partner().fingerprint());
            if (announced == null || announced != p.recognition().serial()) {
                out.println("Écart : reconnaissance de « " + p.recognition().partner().organization() + " » publiée au n° "
                        + p.recognition().serial() + ", preuve au n° " + announced);
                problems++;
            }
        }
        if (problems == 0) {
            out.println("La preuve correspond aux entrées et reconnaissances publiées");
        }
        return problems == 0 ? 0 : 1;
    }

    private static java.util.List<String> scopeOption(Map<String, String> o, String name) {
        return o.containsKey(name) ? java.util.Arrays.stream(o.get(name).split(",")).map(String::strip)
                .filter(g -> !g.isEmpty()).toList() : java.util.List.of();
    }

    private static String describe(java.util.List<String> groups) {
        return groups.isEmpty() ? "aucun" : groups.contains(Recognition.ALL) ? "tous" : String.join(", ", groups);
    }

    /** Renouvelle d'un coup les entrées actives qui expirent bientôt (mêmes clés, adresses et groupes). */
    private int renewAll(Map<String, String> o) throws IOException {
        Organization organization = organization(o);
        long before = Long.parseLong(o.getOrDefault("avant-jours", "15"));
        long days = Long.parseLong(o.getOrDefault("validite-jours", "90"));
        HttpResponse<String> response = calls(o).read("/directory/v1/entries");
        if (response.statusCode() != 200) {
            err.println("Lecture de l'annuaire refusée (HTTP " + response.statusCode() + ") : " + response.body());
            return 1;
        }
        EntryVerifier verifier = new EntryVerifier(organization.id(), organization.publicKey(),
                SerialMemory.inMemory(), clock);
        Instant limit = clock.instant().plus(Duration.ofDays(before));
        java.util.List<SignedDocument> due = new java.util.ArrayList<>();
        for (tools.jackson.databind.JsonNode item : Json.MAPPER.readTree(response.body()).path("entries")) {
            SignedDocument document = SignedDocument.fromJson(item.toString());
            BoxEntry e;
            try {
                e = verifier.verify(document).entry();
            } catch (DirectoryException ex) {
                continue; // entrée d'un autre responsable, ou altérée : jamais renouvelée
            }
            if (e.statusAt(clock.instant()) == BoxEntry.Status.ACTIVE && e.validUntil().isBefore(limit)) {
                out.println("« " + e.displayName() + " » (" + e.boxId() + ") : expire le " + day(e.validUntil()));
                due.add(document);
            }
        }
        if (due.isEmpty()) {
            out.println("Aucune entrée n'expire dans les " + before + " jours");
            return 0;
        }
        if (!confirmed(o, "Renouveler ces " + due.size() + " entrée(s) pour " + days + " jours ? Tapez « oui »")) {
            err.println("Renouvellement abandonné");
            return 1;
        }
        int failures = 0;
        for (SignedDocument document : due) {
            HttpResponse<String> published = post(o, "/directory/v1/entries",
                    organization.renew(document, clock.instant(), Duration.ofDays(days)));
            if (published.statusCode() != 201 && published.statusCode() != 200) {
                err.println("Publication refusée (HTTP " + published.statusCode() + ") : " + published.body());
                failures++;
            }
        }
        out.println((due.size() - failures) + " entrée(s) renouvelée(s)" + (failures == 0 ? "" : ", " + failures
                + " refusée(s)"));
        return failures == 0 ? 0 : 1;
    }

    private boolean confirmed(Map<String, String> o, String question) {
        if (o.containsKey("confirmer")) {
            return true;
        }
        String answer = prompt.answer(question);
        return answer != null && answer.strip().equalsIgnoreCase("oui");
    }

    private static java.util.List<String> groupsOption(Map<String, String> o) {
        return o.containsKey("groupes") ? java.util.Arrays.stream(o.get("groupes").split(",")).map(String::strip)
                .filter(g -> !g.isEmpty()).toList() : java.util.List.of();
    }

    private static String day(Instant instant) {
        return instant.atZone(java.time.ZoneOffset.UTC).toLocalDate().toString();
    }

    private DirectoryCalls calls(Map<String, String> o) {
        return new DirectoryCalls(require(o, "annuaire"), () -> account(o));
    }

    /** Lecture sur le service d'annuaire ; avec le compte du responsable si {@code account}. */
    private HttpResponse<String> get(Map<String, String> o, String path, boolean account) throws IOException {
        return calls(o).get(path, account);
    }

    private String account(Map<String, String> o) {
        String account = o.getOrDefault("compte", environment.get(ACCOUNT_VARIABLE));
        if (account == null || !account.contains(":")) {
            throw new MissingOptionException("Compte de dépôt manquant : --compte utilisateur:mot-de-passe ou "
                    + ACCOUNT_VARIABLE);
        }
        return account;
    }

    /** Dépôt d'un document signé sur le service d'annuaire, avec le compte du responsable. */
    private HttpResponse<String> post(Map<String, String> o, String path, SignedDocument document)
            throws IOException {
        DirectoryCalls calls = calls(o);
        account(o);
        return calls.post(path, document);
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
                  enroler            --cles <clé organisation> --demande <fichier> [--validite-jours 90] [--serie 1]
                                     [--groupes imagerie-nord,urgences] --sortie <fichier>
                  revoquer           --cles <clé organisation> --entree <fichier> --motif <texte> --sortie <fichier>
                  verifier           --cle-publique <fichier> --entree <fichier>
                  publier            --annuaire <url> --entree <fichier> [--compte <utilisateur:mot de passe>]
                  proteger           --cles <fichier> [--box]
                  configuration-signer  --cles <clé organisation> --brouillon <fichier> --sortie <fichier> [--confirmer]
                  configuration-publier --annuaire <url> --configuration <fichier> [--compte <utilisateur:mot de passe>]
                  code-creer         --codes <fichier> --nom <nom de la box> [--groupes a,b] [--validite-jours 30]
                  enroler-attente    --cles <clé organisation> --codes <fichier> --annuaire <url> [--validite-jours 90]
                                     [--confirmer]
                  renouveler         --cles <clé organisation> --annuaire <url> [--avant-jours 15] [--validite-jours 90]
                                     [--confirmer]
                  interface          --cles <clé organisation> --codes <fichier> --annuaire <url> [--validite-jours 90]
                                     [--port 0] [--ouvrir]
                  reconnaitre        --cles <clé organisation> --annuaire <url> --partenaire <clé publique partenaire>
                                     --annuaire-partenaire <url> --relais <url,url> [--groupes-locaux a,b|*]
                                     [--groupes-partenaire c,d|*] [--validite-jours 90] [--confirmer]
                  reconnaissances    --cles <clé organisation> --annuaire <url>
                  reconnaissance-revoquer --cles <clé organisation> --annuaire <url> --empreinte <empreinte du partenaire>
                                     --motif <texte> [--confirmer]
                  fraicheur-deleguer --cles <clé organisation> --sortie-cle <fichier> --sortie-delegation <fichier>
                                     [--validite-jours 30]
                  fraicheur-verifier --cles <clé organisation> --annuaire <url>
                Phrases de passe : IMAGUNITY_PHRASE_ORGANISATION, IMAGUNITY_PHRASE_BOX, ou saisie au terminal ;
                --sans-phrase à la création d'une clé (déconseillé).""");
        return 2;
    }

    /** Options {@code --nom valeur} et drapeaux sans valeur ({@code --sans-phrase}, {@code --box}). */
    private static final java.util.Set<String> FLAGS = java.util.Set.of("sans-phrase", "box", "confirmer", "ouvrir");

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
