package com.imagunity.transport.iu;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.ProtocolException;
import com.imagunity.protocol.SignedMessage;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Boîte de réception IU-MSG d'une box (spécification § 8) : relève sa file sur le relais (attente longue), ouvre et
 * vérifie chaque message, le remet au destinataire, puis l'acquitte.
 *
 * <ul>
 *   <li>message illisible, pas pour cette box, ou mal signé : écarté (acquitté) et journalisé ;</li>
 *   <li>émetteur absent de l'annuaire (pas encore synchronisé, par exemple) : laissé sur le relais, réessayé à la
 *       relève suivante, jusqu'à son expiration ;</li>
 *   <li>message déjà remis (même identifiant signé) : acquitté sans être remis de nouveau (anti-rejeu) ;</li>
 *   <li>échec du traitement : pas d'acquittement, le message sera remis de nouveau (au moins une fois).</li>
 * </ul>
 */
public final class IuMailbox implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(IuMailbox.class.getName());
    /** Durée de mémoire des messages remis (au moins la durée de séjour sur le relais). */
    static final Duration SEEN = Duration.ofDays(8);

    /**
     * File de réception de la box, à enregistrer (son identifiant de relève et sa clé sont secrets).
     *
     * @param relay    relais
     * @param sendId   identifiant de dépôt (publié dans l'annuaire)
     * @param recvId   identifiant de relève
     * @param queueKey clé privée Ed25519 de la file (PKCS#8, base64url)
     */
    public record Queue(String relay, String sendId, String recvId, String queueKey) {

        public QueueAddress address() {
            return new QueueAddress(URI.create(relay), sendId);
        }

        PrivateKey privateKey() {
            return IuFileUploader.privateKey(queueKey);
        }

        public String toJson() {
            return Codec.json().writeValueAsString(this);
        }

        public static Queue fromJson(String json) {
            return Codec.json().readValue(json, Queue.class);
        }
    }

    /** Crée la file de réception de la box sur ce relais. */
    public static Queue create(IuMsgClient client, URI relay, UUID boxId, HybridKeyPair identity) throws IOException {
        KeyPair key;
        try {
            key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        IuMsgClient.Ids ids = client.createQueue(relay, boxId, identity, key.getPublic());
        return new Queue(relay.toString().replaceAll("/+$", ""), ids.sendId(), ids.recvId(),
                Codec.base64Url(key.getPrivate().getEncoded()));
    }

    private final IuMsgClient client;
    private final Queue queue;
    private final HybridKemKeyPair keys;
    private final Function<UUID, Optional<HybridPublicKey>> directory;
    private final Consumer<SignedMessage> handler;
    private final Clock clock;
    private final Map<String, Instant> seen = new LinkedHashMap<>();
    private volatile Thread poller;

    /**
     * @param keys      clés de chiffrement de la box (ouvrir les enveloppes)
     * @param directory clés de signature des box, d'après l'annuaire
     * @param handler   reçoit chaque message vérifié ; une exception laisse le message sur le relais
     */
    public IuMailbox(IuMsgClient client, Queue queue, HybridKemKeyPair keys,
                     Function<UUID, Optional<HybridPublicKey>> directory, Consumer<SignedMessage> handler,
                     Clock clock) {
        this.client = client;
        this.queue = queue;
        this.keys = keys;
        this.directory = directory;
        this.handler = handler;
        this.clock = clock;
    }

    /**
     * Relève la file une fois.
     *
     * @param wait attente longue si la file est vide
     * @return le nombre de messages remis
     */
    public synchronized int pollOnce(Duration wait) throws IOException {
        URI relay = URI.create(queue.relay());
        List<IuMsgClient.Received> received = client.receive(relay, queue.recvId(), queue.privateKey(), wait);
        forgetOld();
        int delivered = 0;
        for (IuMsgClient.Received message : received) {
            SignedMessage opened;
            try {
                opened = SignedMessage.open(message.envelope(), keys, directory);
            } catch (ProtocolException e) {
                if (e.getMessage().contains("absente de l'annuaire")) {
                    LOG.log(Level.WARNING, "Message d''une box absente de l''annuaire, gardé pour plus tard : {0}",
                            e.getMessage());
                    continue;
                }
                LOG.log(Level.WARNING, "Message écarté : {0}", e.getMessage());
                client.ack(relay, queue.recvId(), queue.privateKey(), message.messageId());
                continue;
            }
            if (seen.containsKey(opened.messageId())) {
                client.ack(relay, queue.recvId(), queue.privateKey(), message.messageId());
                continue;
            }
            handler.accept(opened);
            seen.put(opened.messageId(), clock.instant());
            client.ack(relay, queue.recvId(), queue.privateKey(), message.messageId());
            delivered++;
        }
        return delivered;
    }

    /** Relève en continu sur un fil virtuel, jusqu'à {@link #close()}. */
    public void start(Retry.Sleeper sleeper) {
        poller = Thread.ofVirtual().name("iu-relève").start(() -> {
            Duration pause = Duration.ofSeconds(1);
            while (!Thread.currentThread().isInterrupted() && poller != null) {
                try {
                    pollOnce(Duration.ofSeconds(30));
                    pause = Duration.ofSeconds(1);
                } catch (IOException | RuntimeException e) {
                    LOG.log(Level.WARNING, "Relève de la file impossible, nouvel essai : {0}", e.getMessage());
                    try {
                        sleeper.sleep(pause);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                    pause = pause.multipliedBy(2).compareTo(Duration.ofSeconds(30)) > 0 ? Duration.ofSeconds(30)
                            : pause.multipliedBy(2);
                }
            }
        });
    }

    public Queue queue() {
        return queue;
    }

    private void forgetOld() {
        Instant limit = clock.instant().minus(SEEN);
        for (Iterator<Instant> it = seen.values().iterator(); it.hasNext(); ) {
            if (it.next().isBefore(limit)) {
                it.remove();
            }
        }
    }

    @Override
    public void close() {
        Thread running = poller;
        poller = null;
        if (running != null) {
            running.interrupt();
        }
    }
}
