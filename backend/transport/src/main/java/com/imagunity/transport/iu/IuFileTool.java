package com.imagunity.transport.iu;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.protocol.ChunkLayout;
import com.imagunity.protocol.FileDescription;
import com.imagunity.transport.TransportException;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Outil d'essai d'IU-FILE, hors de la box : envoie un fichier par un relais et le reçoit, avec reprise.
 *
 * <pre>
 * envoyer  --relais &lt;url&gt; --cles &lt;identité de box&gt; --fichier &lt;f&gt; --etat &lt;f&gt; --description &lt;f&gt;
 *          [--morceau-mio 4] [--duree-heures 72]
 * recevoir --description &lt;f&gt; --sortie &lt;f&gt; [--fait &lt;f&gt;]
 *
 * file-creer       --relais &lt;url&gt; --cles &lt;identité&gt; --sortie &lt;file.json&gt;
 * message-envoyer  --adresse &lt;adresse de file&gt; --cles &lt;identité&gt; --destinataire-chiffrement &lt;clés&gt;
 *                  --type &lt;type&gt; --corps &lt;texte&gt;
 * message-relever  --file &lt;file.json&gt; --cles &lt;identité&gt; --annuaire &lt;url&gt; --organisation &lt;.pub.json&gt;
 *                  [--attente 30]
 * </pre>
 * Les clés de chiffrement d'une box sont à côté de son identité ({@code <identité>.chiffrement}).
 * L'identifiant de la box est lu dans {@code <identité>.id} ; la phrase de passe de la clé, dans
 * {@code IMAGUNITY_PHRASE_BOX}. Relancé avec le même fichier d'état ({@code --etat}) ou de reprise ({@code --fait}),
 * l'outil reprend là où il s'était arrêté.
 */
public final class IuFileTool {

    private final PrintStream out;
    private final PrintStream err;
    private final Map<String, String> environment;
    private final HttpClient http;
    private final Retry retry;

    IuFileTool(PrintStream out, PrintStream err, Map<String, String> environment, HttpClient http, Retry retry) {
        this.out = out;
        this.err = err;
        this.environment = environment;
        this.http = http;
        this.retry = retry;
    }

    public static void main(String[] args) {
        System.exit(new IuFileTool(System.out, System.err, System.getenv(), HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build(), Retry.standard()).run(args));
    }

    int run(String[] args) {
        if (args.length == 0 || args.length % 2 == 0) {
            return usage();
        }
        Map<String, String> o = new HashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--")) {
                return usage();
            }
            o.put(args[i].substring(2), args[i + 1]);
        }
        try {
            return switch (args[0]) {
                case "envoyer" -> send(o);
                case "recevoir" -> receive(o);
                case "file-creer" -> createQueue(o);
                case "message-envoyer" -> sendMessage(o);
                case "message-relever" -> receiveMessages(o);
                default -> usage();
            };
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return usage();
        } catch (IOException | RuntimeException e) {
            err.println("Erreur : " + e.getMessage());
            return 1;
        }
    }

    private int send(Map<String, String> o) throws IOException {
        Path keys = Path.of(require(o, "cles"));
        HybridKeyPair identity = identity(keys);
        UUID boxId = boxId(keys);
        Path file = Path.of(require(o, "fichier"));
        Path statePath = Path.of(require(o, "etat"));
        IuFileUploader uploader = new IuFileUploader(new IuFileClient(URI.create(require(o, "relais")), http,
                Clock.systemUTC()), boxId, identity, Clock.systemUTC(), retry);
        IuFileUploader.UploadState state;
        if (Files.exists(statePath)) {
            state = IuFileUploader.UploadState.fromJson(Files.readString(statePath));
            out.println("Reprise : " + state.chunks().stream().filter(IuFileUploader.ChunkState::uploaded).count()
                    + " morceau(x) déjà téléversé(s) sur " + state.chunks().size());
        } else {
            int chunkSize = Integer.parseInt(o.getOrDefault("morceau-mio", "4")) * ChunkLayout.MIB;
            state = uploader.prepare(file, chunkSize, Duration.ofHours(Long.parseLong(
                    o.getOrDefault("duree-heures", "72"))));
            save(statePath, state);
            out.println("Morceaux créés sur le relais : " + state.chunks().size());
        }
        long start = System.nanoTime();
        IuFileUploader.UploadState done = uploader.upload(file, state, s -> save(statePath, s), n -> { });
        Files.writeString(Path.of(require(o, "description")), IuFileUploader.description(done).toJson());
        out.printf("Fichier téléversé (%d octets) en %.1f s ; description écrite%n", done.size(),
                (System.nanoTime() - start) / 1e9);
        return 0;
    }

    private int receive(Map<String, String> o) throws IOException {
        FileDescription description = FileDescription.fromJson(Files.readString(Path.of(require(o, "description"))));
        Path target = Path.of(require(o, "sortie"));
        Path donePath = Path.of(o.getOrDefault("fait", target + ".fait"));
        Set<Integer> done = new HashSet<>();
        if (Files.exists(donePath)) {
            done.addAll(Files.readAllLines(donePath).stream().filter(l -> !l.isBlank()).map(Integer::parseInt)
                    .collect(Collectors.toSet()));
            out.println("Reprise : " + done.size() + " morceau(x) déjà reçu(s) sur " + description.chunkCount());
        }
        long start = System.nanoTime();
        new IuFileDownloader(http, Clock.systemUTC(), retry).download(description, target, done, index -> {
            try {
                Files.writeString(donePath, index + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new TransportException("Reprise non enregistrée : " + e.getMessage(), e);
            }
        }, n -> { });
        out.printf("Fichier reçu et vérifié (%d octets, SHA-256 %s) en %.1f s%n", description.size(),
                description.digest(), (System.nanoTime() - start) / 1e9);
        return 0;
    }

    private int createQueue(Map<String, String> o) throws IOException {
        Path keys = Path.of(require(o, "cles"));
        IuMailbox.Queue queue = IuMailbox.create(new IuMsgClient(http, Clock.systemUTC()),
                URI.create(require(o, "relais")), boxId(keys), identity(keys));
        Files.writeString(Path.of(require(o, "sortie")), queue.toJson());
        out.println("File créée ; adresse à publier dans l'annuaire : " + queue.address());
        return 0;
    }

    private int sendMessage(Map<String, String> o) throws IOException {
        Path keys = Path.of(require(o, "cles"));
        com.imagunity.directory.HybridKemPublicKey recipient = KeyFiles.readEncryptionPrivate(
                Path.of(require(o, "destinataire-chiffrement")), phrase()).publicKey();
        IuMessenger messenger = new IuMessenger(new IuMsgClient(http, Clock.systemUTC()), boxId(keys),
                identity(keys), Clock.systemUTC(), retry);
        com.imagunity.protocol.SignedMessage sent = messenger.send(QueueAddress.parse(require(o, "adresse")),
                recipient, require(o, "type"), require(o, "corps"));
        out.println("Message " + sent.type() + " déposé (" + sent.messageId() + ")");
        return 0;
    }

    private int receiveMessages(Map<String, String> o) throws IOException {
        Path keys = Path.of(require(o, "cles"));
        IuMailbox.Queue queue = IuMailbox.Queue.fromJson(Files.readString(Path.of(require(o, "file"))));
        KeyFiles.OrganizationPublicKey organization = KeyFiles.readOrganizationPublic(
                Path.of(require(o, "organisation")));
        com.imagunity.directory.LocalDirectory directory = new com.imagunity.directory.LocalDirectory(
                new com.imagunity.directory.EntryVerifier(organization.organization(), organization.key(),
                        com.imagunity.directory.SerialMemory.inMemory(), Clock.systemUTC()),
                new com.imagunity.directory.EntryCache() {
                    @Override
                    public void save(UUID boxId, long serial, com.imagunity.directory.SignedDocument document,
                                     java.time.Instant fetchedAt) {
                    }

                    @Override
                    public java.util.List<com.imagunity.directory.SignedDocument> entries() {
                        return java.util.List.of();
                    }
                }, Clock.systemUTC());
        directory.synchronize(new com.imagunity.directory.DirectoryClient(URI.create(require(o, "annuaire")),
                Duration.ofSeconds(10))::fetch);
        IuMailbox mailbox = new IuMailbox(new IuMsgClient(http, Clock.systemUTC()), queue,
                KeyFiles.readEncryptionPrivate(Path.of(keys + ".chiffrement"), phrase()),
                id -> directory.entry(id).map(com.imagunity.directory.BoxEntry::signingKey),
                message -> out.println("Reçu de " + message.boxId() + " : " + message.type() + " « " + message.body()
                        + " » (émis " + message.createdAt() + ")"), Clock.systemUTC());
        int delivered = mailbox.pollOnce(Duration.ofSeconds(Long.parseLong(o.getOrDefault("attente", "30"))));
        out.println(delivered + " message(s) relevé(s)");
        return 0;
    }

    private char[] phrase() {
        String phrase = environment.get("IMAGUNITY_PHRASE_BOX");
        return phrase == null || phrase.isEmpty() ? null : phrase.toCharArray();
    }

    private HybridKeyPair identity(Path keys) throws IOException {
        return KeyFiles.readPrivate(keys, phrase());
    }

    private static UUID boxId(Path keys) throws IOException {
        return UUID.fromString(Files.readString(Path.of(keys + ".id")).strip());
    }

    private static void save(Path path, IuFileUploader.UploadState state) {
        try {
            Path temporary = path.resolveSibling(path.getFileName() + ".nouveau");
            Files.writeString(temporary, state.toJson());
            Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new TransportException("État d'envoi non enregistré : " + e.getMessage(), e);
        }
    }

    private static String require(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Option manquante : --" + name);
        }
        return value;
    }

    private int usage() {
        err.println("""
                Usage :
                  envoyer  --relais <url> --cles <identité de box> --fichier <f> --etat <f> --description <f>
                           [--morceau-mio 4] [--duree-heures 72]
                  recevoir --description <f> --sortie <f> [--fait <f>]
                  file-creer       --relais <url> --cles <identité> --sortie <file.json>
                  message-envoyer  --adresse <adresse> --cles <identité> --destinataire-chiffrement <clés>
                                   --type <type> --corps <texte>
                  message-relever  --file <file.json> --cles <identité> --annuaire <url> --organisation <.pub.json>
                                   [--attente 30]""");
        return 2;
    }
}
