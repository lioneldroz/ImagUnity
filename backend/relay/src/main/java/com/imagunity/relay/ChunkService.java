package com.imagunity.relay;

import com.imagunity.protocol.ChunkLayout;
import com.imagunity.protocol.Codec;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/**
 * Règles d'IU-FILE (spécification § 6.2) : création, téléversement vérifié, retrait par capacité, acquittement,
 * suppression, expiration, quotas.
 */
public final class ChunkService {

    private static final System.Logger LOG = System.getLogger(ChunkService.class.getName());
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    static final int MAX_RECIPIENTS = 16;

    /**
     * Création demandée par une box.
     *
     * @param suite         {@code IU1}
     * @param size          taille du morceau (une taille admise)
     * @param digest        empreinte SHA-256 attendue
     * @param senderKey     clé publique Ed25519 de l'émetteur (X.509, base64url)
     * @param recipientKeys clés publiques de retrait, une par destinataire
     * @param ttl           durée de séjour demandée, en secondes (facultative)
     */
    public record CreateRequest(String suite, int size, String digest, String senderKey, List<String> recipientKeys,
                                Long ttl) {
    }

    /** Identifiants attribués. */
    public record Created(String senderId, List<String> recipientIds) {
    }

    /** État vu par l'émetteur, sans identifier les destinataires. */
    public record State(boolean uploaded, int recipientsAcked, int recipients) {
    }

    private final ChunkStore store;
    private final ChunkFiles files;
    private final RelayProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public ChunkService(ChunkStore store, ChunkFiles files, RelayProperties properties, Clock clock) {
        this.store = store;
        this.files = files;
        this.properties = properties;
        this.clock = clock;
    }

    public Created create(UUID boxId, CreateRequest request) throws IOException {
        if (!"IU1".equals(request.suite())) {
            throw new RelayException(HttpStatus.BAD_REQUEST, "Suite non prise en charge : " + request.suite());
        }
        if (!ChunkLayout.SIZES.contains(request.size())) {
            throw new RelayException(HttpStatus.BAD_REQUEST, "Taille de morceau non admise : " + request.size());
        }
        if (request.digest() == null || !SHA256.matcher(request.digest()).matches()) {
            throw new RelayException(HttpStatus.BAD_REQUEST, "Empreinte invalide");
        }
        RequestAuthenticator.publicKey(request.senderKey());
        List<String> keys = request.recipientKeys() == null ? List.of() : request.recipientKeys();
        if (keys.isEmpty() || keys.size() > MAX_RECIPIENTS) {
            throw new RelayException(HttpStatus.BAD_REQUEST, "Entre 1 et " + MAX_RECIPIENTS + " destinataires");
        }
        keys.forEach(RequestAuthenticator::publicKey);
        if (store.bytesOf(boxId) + request.size() > properties.quotaPerBox().toBytes()) {
            throw new RelayException(HttpStatus.INSUFFICIENT_STORAGE, "Volume maximal déposé par cette box atteint");
        }
        if (Files.getFileStore(files.root()).getUsableSpace() - request.size() < properties.minFreeSpace().toBytes()) {
            throw new RelayException(HttpStatus.INSUFFICIENT_STORAGE, "Espace disque du relais insuffisant");
        }
        Duration ttl = request.ttl() == null || request.ttl() <= 0 ? properties.defaultTtl()
                : Duration.ofSeconds(request.ttl());
        if (ttl.compareTo(properties.maxTtl()) > 0) {
            ttl = properties.maxTtl();
        }
        Instant now = clock.instant();
        String senderId = id();
        List<ChunkStore.Recipient> recipients = new ArrayList<>();
        for (String key : keys) {
            recipients.add(new ChunkStore.Recipient(id(), senderId, key, false));
        }
        store.create(new ChunkStore.Chunk(senderId, boxId, request.senderKey(), request.size(), request.digest(),
                false, now, now.plus(ttl)), recipients);
        return new Created(senderId, recipients.stream().map(ChunkStore.Recipient::recipientId).toList());
    }

    /** Morceau d'un émetteur ; 404 sinon. */
    public ChunkStore.Chunk chunk(String senderId) {
        return store.chunk(senderId).filter(c -> c.expiresAt().isAfter(clock.instant()))
                .orElseThrow(() -> new RelayException(HttpStatus.NOT_FOUND, "Morceau inconnu ou expiré"));
    }

    /** Destinataire non acquitté d'un morceau non expiré ; 404 sinon (sans dire pourquoi). */
    public ChunkStore.Recipient recipient(String recipientId) {
        ChunkStore.Recipient recipient = store.recipient(recipientId).filter(r -> !r.acked())
                .orElseThrow(() -> new RelayException(HttpStatus.NOT_FOUND, "Morceau inconnu ou expiré"));
        chunk(recipient.senderId());
        return recipient;
    }

    /** Met en place un téléversement reçu, s'il a la taille et l'empreinte annoncées. */
    public void upload(ChunkStore.Chunk chunk, ChunkFiles.Received received) throws IOException {
        try {
            if (received.size() != chunk.size() || !received.sha256().equals(chunk.digest())) {
                throw new RelayException(HttpStatus.BAD_REQUEST,
                        "Morceau différent de celui annoncé (taille ou empreinte)");
            }
            if (chunk.uploaded()) {
                return; // déjà reçu : renvoi après une réponse perdue
            }
            files.commit(received, chunk.senderId());
            store.markUploaded(chunk.senderId());
        } finally {
            Files.deleteIfExists(received.temp());
        }
    }

    /**
     * Contenu d'un morceau, en attendant au plus {@code wait} s'il n'est pas encore téléversé.
     *
     * @throws RelayException 425 si le morceau n'est toujours pas téléversé
     */
    public Path download(ChunkStore.Recipient recipient, Duration wait) throws InterruptedException {
        Duration bounded = wait.compareTo(properties.maxWait()) > 0 ? properties.maxWait() : wait;
        Instant deadline = clock.instant().plus(bounded);
        while (true) {
            if (chunk(recipient.senderId()).uploaded()) {
                return files.path(recipient.senderId());
            }
            if (!clock.instant().isBefore(deadline)) {
                throw new RelayException(HttpStatus.TOO_EARLY, "Morceau pas encore téléversé");
            }
            Thread.sleep(200);
        }
    }

    /** Acquitte un retrait ; le morceau est supprimé quand tous ses destinataires l'ont acquitté. */
    public void ack(ChunkStore.Recipient recipient) throws IOException {
        store.ack(recipient.recipientId());
        if (store.recipients(recipient.senderId()).stream().allMatch(ChunkStore.Recipient::acked)) {
            delete(recipient.senderId());
        }
    }

    public State state(ChunkStore.Chunk chunk) {
        List<ChunkStore.Recipient> recipients = store.recipients(chunk.senderId());
        return new State(chunk.uploaded(), (int) recipients.stream().filter(ChunkStore.Recipient::acked).count(),
                recipients.size());
    }

    public void delete(String senderId) throws IOException {
        store.delete(senderId);
        files.delete(senderId);
    }

    /** Supprime les morceaux expirés, et ceux créés mais jamais téléversés à temps. */
    public int purge() {
        Instant now = clock.instant();
        List<String> expired = store.expired(now, now.minus(properties.incompleteTtl()));
        for (String senderId : expired) {
            try {
                delete(senderId);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Morceau expiré non supprimé : {0}", e.getMessage());
            }
        }
        if (!expired.isEmpty()) {
            LOG.log(Level.INFO, "{0} morceau(x) expiré(s) supprimé(s)", expired.size());
        }
        return expired.size();
    }

    private String id() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Codec.base64Url(bytes);
    }
}
