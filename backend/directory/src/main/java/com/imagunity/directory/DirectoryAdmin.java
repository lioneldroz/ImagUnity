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
 * box-demande        --cles &lt;fichier&gt; --box-id &lt;uuid&gt; --nom &lt;nom&gt; [--simplex &lt;adresse&gt;] --sortie &lt;fichier&gt;
 * enroler            --cles &lt;clé organisation&gt; --demande &lt;fichier&gt; [--validite-jours 365] [--serie 1] --sortie &lt;fichier&gt;
 * revoquer           --cles &lt;clé organisation&gt; --entree &lt;fichier&gt; --motif &lt;texte&gt; --sortie &lt;fichier&gt;
 * verifier           --cle-publique &lt;fichier&gt; --entree &lt;fichier&gt;
 * publier            --annuaire &lt;url&gt; --entree &lt;fichier&gt; [--compte &lt;utilisateur:mot de passe&gt;]
 * </pre>
 * Pour {@code publier}, le compte peut venir de la variable {@code IMAGUNITY_ANNUAIRE_COMPTE} (préférable : un
 * argument de ligne de commande est visible des autres utilisateurs de la machine).
 */
public final class DirectoryAdmin {

    static final String ACCOUNT_VARIABLE = "IMAGUNITY_ANNUAIRE_COMPTE";

    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;
    private final Map<String, String> environment;

    DirectoryAdmin(PrintStream out, PrintStream err, Clock clock, Map<String, String> environment) {
        this.out = out;
        this.err = err;
        this.clock = clock;
        this.environment = environment;
    }

    public static void main(String[] args) {
        System.exit(new DirectoryAdmin(System.out, System.err, Clock.systemUTC(), System.getenv()).run(args));
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
        KeyFiles.writePrivate(keys, pair);
        Path publicFile = Path.of(keys + ".pub.json");
        KeyFiles.writeOrganizationPublic(publicFile, organization, pair.publicKey());
        out.println("Clé du responsable de « " + organization + " » créée : " + keys + " (à garder hors ligne)");
        out.println("Clé publique, à installer dans les box : " + publicFile);
        out.println("Empreinte à communiquer hors bande : " + pair.publicKey().shortCode());
        return 0;
    }

    private int fingerprint(Map<String, String> o) throws IOException {
        KeyFiles.OrganizationPublicKey key = KeyFiles.readOrganizationPublic(Path.of(require(o, "cle-publique")));
        out.println("Organisation « " + key.organization() + " » : " + key.key().shortCode());
        return 0;
    }

    private int boxRequest(Map<String, String> o) throws IOException {
        Path keysFile = Path.of(require(o, "cles"));
        HybridKeyPair keys;
        if (Files.exists(keysFile)) {
            keys = KeyFiles.readPrivate(keysFile);
        } else {
            keys = HybridKeyPair.generate();
            KeyFiles.writePrivate(keysFile, keys);
            out.println("Identité de la box créée : " + keysFile);
        }
        Map<String, String> transport = new LinkedHashMap<>();
        if (o.containsKey("simplex")) {
            transport.put("simplex", o.get("simplex"));
        }
        SignedDocument request = EnrollmentRequest.create(keys, UUID.fromString(require(o, "box-id")),
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
        return verified.usable() ? 0 : 1;
    }

    private int publish(Map<String, String> o) throws IOException {
        String directory = require(o, "annuaire").replaceAll("/+$", "");
        SignedDocument entry = read(Path.of(require(o, "entree")));
        String account = o.getOrDefault("compte", environment.get(ACCOUNT_VARIABLE));
        if (account == null || !account.contains(":")) {
            throw new MissingOptionException("Compte de dépôt manquant : --compte utilisateur:mot-de-passe ou "
                    + ACCOUNT_VARIABLE);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(directory + "/directory/v1/entries"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString(account.getBytes(StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(entry.toJson()))
                .build();
        HttpResponse<String> response;
        try (HttpClient http = HttpClient.newHttpClient()) {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Publication interrompue", e);
        }
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

    private Organization organization(Map<String, String> o) throws IOException {
        Path keys = Path.of(require(o, "cles"));
        KeyFiles.OrganizationPublicKey publicKey = KeyFiles.readOrganizationPublic(Path.of(keys + ".pub.json"));
        HybridKeyPair pair = KeyFiles.readPrivate(keys);
        if (!pair.publicKey().fingerprint().equals(publicKey.key().fingerprint())) {
            throw new DirectoryException("La clé privée ne correspond pas à la clé publique de l'organisation");
        }
        return new Organization(publicKey.organization(), pair);
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
                  box-demande        --cles <fichier> --box-id <uuid> --nom <nom> [--simplex <adresse>] --sortie <fichier>
                  enroler            --cles <clé organisation> --demande <fichier> [--validite-jours 365] [--serie 1] --sortie <fichier>
                  revoquer           --cles <clé organisation> --entree <fichier> --motif <texte> --sortie <fichier>
                  verifier           --cle-publique <fichier> --entree <fichier>
                  publier            --annuaire <url> --entree <fichier> [--compte <utilisateur:mot de passe>]""");
        return 2;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("Option mal formée : " + args[i]);
            }
            options.put(args[i].substring(2), args[i + 1]);
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
