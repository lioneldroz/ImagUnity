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
    /** Part d'une file qu'une seule box émettrice peut occuper : elle ne peut pas la remplir pour les autres. */
    static final int MAX_PENDING_PER_SENDER = 2_000;
    static final int BATCH = 20;

    /**
     * Création demandée par une box.
     *
     * @param suite    {@code IU1}
     * @param queueKey clé publique Ed25519 de la file (X.509, base64url)
     * @param sendId   identifiant de dépôt réservé par la box et publié dans son entrée d'annuaire signée, ou
     *                 {@code null} pour un identifiant tiré par le relais
     */
    public record CreateRequest(String suite, String queueKey, String sendId) {

        public CreateRequest(String suite, String queueKey) {
            this(suite, queueKey, null);
        }
    }

    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final java.util.regex.Pattern PUBLISHED = java.util.regex.Pattern.compile(
            ".*/iu/v1/queues/([A-Za-z0-9_-]{43})");

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

    private final java.util.function.Function<UUID, java.util.List<String>> publishedAddresses;

    /**
     * @param publishedAddresses adresses de file publiées dans l'entrée d'annuaire de chaque box ({@code transport.iu},
     *                           {@code transport.iu-2}… : une par relais de la box)
     */
    public QueueService(QueueStore store, RelayProperties properties, Clock clock,
                        java.util.function.Function<UUID, java.util.List<String>> publishedAddresses) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.publishedAddresses = publishedAddresses;
    }

    public Ids create(UUID boxId, CreateRequest request) {
        if (request == null || !"IU1".equals(request.suite())) {
            throw new RelayException(HttpStatus.BAD_REQUEST, "Suite non prise en charge");
        }
        RequestAuthenticator.publicKey(request.queueKey());
        if (store.countOf(boxId) >= properties.maxQueuesPerBox()) {
            throw new RelayException(HttpStatus.INSUFFICIENT_STORAGE, "Nombre maximal de files de cette box atteint");
        }
        String sendId = request.sendId() == null ? id(32) : reserved(boxId, request.sendId());
        Ids ids = new Ids(sendId, id(32));
        store.create(new QueueStore.Queue(ids.recvId(), ids.sendId(), boxId, request.queueKey(), clock.instant()));
        return ids;
    }

    /**
     * Identifiant de dépôt réservé par la box avant son enrôlement : admis seulement s'il est celui d'une adresse
     * publiée dans son entrée d'annuaire signée (aucune autre box ne peut se l'approprier), et pas encore pris.
     */
    private String reserved(UUID boxId, String sendId) {
        if (!ID.matcher(sendId).matches()) {
            throw new RelayException(HttpStatus.BAD_REQUEST, "Identifiant de dépôt invalide");
        }
        boolean published = publishedAddresses.apply(boxId).stream().filter(java.util.Objects::nonNull)
                .map(PUBLISHED::matcher).filter(java.util.regex.Matcher::matches)
                .anyMatch(m -> sendId.equals(m.group(1)));
        if (!published) {
            throw new RelayException(HttpStatus.CONFLICT,
                    "Identifiant de dépôt absent de l'entrée d'annuaire de la box");
        }
        if (store.bySendId(sendId).isPresent()) {
            throw new RelayException(HttpStatus.CONFLICT, "File déjà créée");
        }
        return sendId;
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
        if (store.pendingFrom(queue.recvId(), senderBox) >= MAX_PENDING_PER_SENDER) {
            throw new RelayException(HttpStatus.TOO_MANY_REQUESTS,
                    "Trop de messages en attente de cette box pour ce destinataire");
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
        if (!store.rotate(queue, ids.recvId(), ids.sendId())) {
            throw new RelayException(HttpStatus.NOT_FOUND, "File inconnue");
        }
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
