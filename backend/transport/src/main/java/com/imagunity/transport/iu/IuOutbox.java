package com.imagunity.transport.iu;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.protocol.Codec;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Boîte d'envoi IU-MSG : les messages scellés sont enregistrés, puis déposés en arrière-plan dans la file du
 * destinataire. Comme une poste restante côté émetteur, un relais injoignable ne bloque pas l'orchestrateur : le dépôt
 * est réessayé jusqu'au succès, ou jusqu'à {@code maxAge}.
 *
 * <ul>
 *   <li>ordre de dépôt conservé pour chaque destinataire ; un destinataire injoignable ne retarde pas les autres ;</li>
 *   <li>l'adresse de la file est relue dans l'annuaire à chaque essai (file renouvelée, entrée réémise) ;</li>
 *   <li>refus définitif du relais (message mal formé ou trop long) : message abandonné et journalisé. Les refus « box
 *       absente ou inactive de l'annuaire du relais » sont réessayés : le relais peut ne pas avoir encore vu une
 *       entrée récente.</li>
 * </ul>
 */
final class IuOutbox implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(IuOutbox.class.getName());
    static final Duration MAX_PAUSE = Duration.ofSeconds(30);

    private final IuStore store;
    private final IuMsgClient client;
    private final UUID boxId;
    private final HybridKeyPair identity;
    private final Function<UUID, Optional<QueueAddress>> addressOf;
    private final Clock clock;
    private final Duration maxAge;
    private final Semaphore signal = new Semaphore(0);
    private volatile Thread sender;
    private volatile boolean running;

    IuOutbox(IuStore store, IuMsgClient client, UUID boxId, HybridKeyPair identity,
             Function<UUID, Optional<QueueAddress>> addressOf, Clock clock, Duration maxAge) {
        this.store = store;
        this.client = client;
        this.boxId = boxId;
        this.identity = identity;
        this.addressOf = addressOf;
        this.clock = clock;
        this.maxAge = maxAge;
    }

    /** Enregistre un message pour {@code to} ; il sera déposé dès que possible. */
    void enqueue(UUID to, byte[] envelope) {
        store.addPending(to, envelope, clock.instant());
        signal.release();
    }

    /**
     * Dépose les messages en attente une fois.
     *
     * @return vrai si tout a été déposé (ou abandonné), faux s'il en reste pour plus tard
     */
    boolean flush() {
        Set<UUID> blocked = new HashSet<>();
        for (IuStore.Pending pending : store.pending()) {
            if (blocked.contains(pending.to())) {
                continue;
            }
            if (pending.createdAt().plus(maxAge).isBefore(clock.instant())) {
                LOG.log(Level.WARNING, "Message pour la box {0} jamais déposé depuis {1} : abandonné", pending.to(),
                        pending.createdAt());
                store.removePending(pending.seq());
                continue;
            }
            Optional<QueueAddress> address = addressOf.apply(pending.to());
            if (address.isEmpty()) {
                LOG.log(Level.WARNING, "Box {0} sans adresse IU-T active dans l''annuaire : message gardé",
                        pending.to());
                blocked.add(pending.to());
                continue;
            }
            try {
                client.deposit(address.get(), boxId, identity, Codec.fromBase64Url(pending.envelope(), "enveloppe"));
                store.removePending(pending.seq());
            } catch (RelayRefusedException e) {
                if (e.status() == 400 || e.status() == 413) {
                    LOG.log(Level.WARNING, "Message pour la box {0} refusé par le relais, abandonné : {1}",
                            pending.to(), e.getMessage());
                    store.removePending(pending.seq());
                } else {
                    LOG.log(Level.INFO, "Dépôt pour la box {0} à réessayer : {1}", pending.to(), e.getMessage());
                    blocked.add(pending.to());
                }
            } catch (IOException e) {
                LOG.log(Level.INFO, "Dépôt pour la box {0} à réessayer : {1}", pending.to(), e.getMessage());
                blocked.add(pending.to());
            }
        }
        return blocked.isEmpty();
    }

    /** Dépose en continu sur un fil virtuel, jusqu'à {@link #close()}. */
    void start() {
        running = true;
        sender = Thread.ofVirtual().name("iu-envoi").start(() -> {
            Duration pause = Duration.ofSeconds(1);
            while (running) {
                boolean done;
                try {
                    done = flush();
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "Boîte d''envoi IU-T : {0}", e.getMessage());
                    done = false;
                }
                try {
                    if (done) {
                        pause = Duration.ofSeconds(1);
                        signal.tryAcquire(MAX_PAUSE.toMillis(), TimeUnit.MILLISECONDS);
                    } else {
                        signal.tryAcquire(pause.toMillis(), TimeUnit.MILLISECONDS);
                        pause = pause.multipliedBy(2).compareTo(MAX_PAUSE) > 0 ? MAX_PAUSE : pause.multipliedBy(2);
                    }
                    signal.drainPermits();
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
    }

    @Override
    public void close() {
        running = false;
        Thread thread = sender;
        sender = null;
        if (thread != null) {
            thread.interrupt();
        }
    }
}
