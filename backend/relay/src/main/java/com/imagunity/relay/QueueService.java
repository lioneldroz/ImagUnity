package com.imagunity.relay;

import com.imagunity.protocol.Codec;
import java.lang.System.Logger.Level;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Règles d'IU-MSG (spécification § 8.2) : files de réception, dépôt réservé aux box actives, relève par attente
 * longue, acquittement, renouvellement, expiration.
 */
public final class QueueService {

    private static final System.Logger LOG = System.getLogger(QueueService.class.getName());
    /** Taille maximale d'une enveloppe : contenu de 64 Kio, plus en-tête et étiquette. */
    static final int MAX_ENVELOPE = 72 * 1024;
    static final int MAX_PENDING = 10_000;
    static final int BATCH = 20;

    /**
     * Création demandée par une box.
     *
     * @param suite    {@code IU1}
     * @param queueKey clé publique Ed25519 de la file (X.509, base64url)
     */
    public record CreateRequest(String suite, String queueKey) {
    }

    /** Identifiants d'une file. */
    public record Ids(String sendId, String recvId) {
    }

    /** Message déposé. */
    public record Deposited(String messageId) {
    }

    /** Message rendu à la relève. */
    public record Delivered(String messageId, Instant receivedAt, String content) {
    }

    private final QueueStore store;
    private final RelayProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public QueueService(QueueStore store, RelayProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    public Ids create(UUID boxId, CreateRequest request) {
        if (request == null || !"IU1".equals(request.suite())) {
            throw new RelayException(HttpStatus.BAD_REQUEST, "Suite non prise en charge");
        }
        RequestAuthenticator.publicKey(request.queueKey());
        if (store.countOf(boxId) >= properties.maxQueuesPerBox()) {
            throw new RelayException(HttpStatus.INSUFFICIENT_STORAGE, "Nombre maximal de files de cette box atteint");
        }
        Ids ids = new Ids(id(32), id(32));
        store.create(new QueueStore.Queue(ids.recvId(), ids.sendId(), boxId, request.queueKey(), clock.instant()));
        return ids;
    }

    /** File désignée par son identifiant de dépôt ; 404 sinon. */
    public QueueStore.Queue bySendId(String sendId) {
        return store.bySendId(sendId).orElseThrow(() -> new RelayException(HttpStatus.NOT_FOUND, "File inconnue"));
    }

    /** File désignée par son identifiant de relève ; 404 sinon. */
    public QueueStore.Queue byRecvId(String recvId) {
        return store.byRecvId(recvId).orElseThrow(() -> new RelayException(HttpStatus.NOT_FOUND, "File inconnue"));
    }

    public Deposited deposit(QueueStore.Queue queue, UUID senderBox, byte[] envelope) {
        if (envelope.length == 0 || envelope.length > MAX_ENVELOPE) {
            throw new RelayException(HttpStatus.CONTENT_TOO_LARGE, "Message vide ou trop long");
        }
        if (store.pending(queue.recvId()) >= MAX_PENDING) {
            throw new RelayException(HttpStatus.INSUFFICIENT_STORAGE, "File pleine : la box destinataire ne relève plus");
        }
        Instant now = clock.instant();
        String messageId = id(16);
        store.deposit(queue.recvId(), messageId, senderBox, envelope, now, now.plus(properties.defaultTtl()));
        return new Deposited(messageId);
    }

    /** Messages en attente ; attend au plus {@code wait} (plafonné) s'il n'y en a aucun. */
    public List<Delivered> receive(QueueStore.Queue queue, Duration wait) throws InterruptedException {
        Duration bounded = wait.compareTo(properties.maxWait()) > 0 ? properties.maxWait() : wait;
        Instant deadline = clock.instant().plus(bounded);
        while (true) {
            List<QueueStore.Message> messages = store.messages(queue.recvId(), clock.instant(), BATCH);
            if (!messages.isEmpty() || !clock.instant().isBefore(deadline)) {
                return messages.stream().map(m -> new Delivered(m.messageId(), m.receivedAt(),
                        Codec.base64Url(m.content()))).toList();
            }
            Thread.sleep(200);
        }
    }

    public void ack(QueueStore.Queue queue, String messageId) {
        if (!store.delete(queue.recvId(), messageId)) {
            throw new RelayException(HttpStatus.NOT_FOUND, "Message inconnu");
        }
    }

    public Ids rotate(QueueStore.Queue queue) {
        Ids ids = new Ids(id(32), id(32));
        store.rotate(queue, ids.recvId(), ids.sendId());
        return ids;
    }

    public int purge() {
        int purged = store.purge(clock.instant());
        if (purged > 0) {
            LOG.log(Level.INFO, "{0} message(s) expiré(s) supprimé(s)", purged);
        }
        return purged;
    }

    private String id(int bytes) {
        byte[] value = new byte[bytes];
        random.nextBytes(value);
        return Codec.base64Url(value);
    }
}
