package com.imagunity.orchestration;

import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportPort;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Appairage des box par l'annuaire.
 *
 * <ol>
 *   <li>L'opérateur de la box A choisit une box active de l'annuaire et le nom sous lequel elle sera enregistrée
 *       ({@code box-b}) ; A se connecte à l'adresse de contact que publie son entrée ({@link #join}) ;</li>
 *   <li>à la connexion, A enregistre le partenaire sous le nom choisi par <b>son</b> opérateur ;</li>
 *   <li>en face, la connexion entrante est enregistrée comme partenaire provisoire {@code attente-<n>} : elle n'est
 *       autorisée à rien tant que la poignée de main signée ne l'a pas liée à une box active de l'annuaire, qui la
 *       nomme alors (voir {@link PeerHandshake}).</li>
 * </ol>
 * Le nom vient de l'opérateur local ou de l'annuaire, jamais du profil annoncé par le partenaire. Un seul appairage
 * peut être en cours à la fois ; il expire après le délai configuré.
 */
public final class PairingService {

    private static final System.Logger LOG = System.getLogger(PairingService.class.getName());

    /**
     * Appairage demandé par l'opérateur.
     *
     * @param expectedBox box visée (d'après l'annuaire), ou {@code null} si le transport ne l'authentifie pas
     */
    private record Pending(String name, Instant since, java.util.UUID expectedBox) {
    }

    /** Préfixe des partenaires provisoires (connexion entrante pas encore vérifiée par l'annuaire). */
    public static final String PROVISIONAL_PREFIX = "attente-";

    private final PeerRepository peers;
    private final TransportPort transport;
    private final Clock clock;
    private final Duration pendingTimeout;
    private Pending pending;

    public PairingService(PeerRepository peers, TransportPort transport, Clock clock, Duration pendingTimeout) {
        this.peers = peers;
        this.transport = transport;
        this.clock = clock;
        this.pendingTimeout = pendingTimeout;
    }

    public void start() {
        transport.addListener(this::onTransportEvent);
    }

    /** Se connecte à la box qui publie {@code address} dans l'annuaire, enregistrée localement sous {@code name}. */
    public synchronized void join(String name, String address) {
        join(name, address, null);
    }

    /**
     * Se connecte à la box {@code expectedBox} de l'annuaire, qui publie {@code address} : seule une connexion de cette
     * box, authentifiée par le transport, reçoit le nom choisi.
     */
    public synchronized void join(String name, String address, java.util.UUID expectedBox) {
        Peer.requireValidName(name);
        // Avant l'appel : la connexion peut être signalée avant son retour.
        pending = new Pending(name, clock.instant(), expectedBox);
        try {
            transport.connect(address);
        } catch (RuntimeException e) {
            pending = null;
            throw e;
        }
        LOG.log(Level.INFO, "Connexion demandée pour le partenaire {0}", name);
    }

    public List<Peer> peers() {
        return peers.findAll();
    }

    synchronized void onTransportEvent(TransportEvent event) {
        if (!(event instanceof TransportEvent.PeerConnected connected)) {
            return;
        }
        long peerId = connected.peer().value();
        Instant now = clock.instant();
        boolean expected = pending != null && !pending.since().plus(pendingTimeout).isBefore(now)
                && (pending.expectedBox() == null || transport.authenticatedBox(connected.peer())
                .map(pending.expectedBox()::equals).orElse(false));
        Optional<Peer> known = peers.findByTransportPeerId(peerId);
        if (known.isPresent()) {
            // Déjà connue (contact provisoire) : si c'est la box attendue, elle prend le nom choisi par l'opérateur.
            if (expected && known.get().name().startsWith(PROVISIONAL_PREFIX)) {
                peers.delete(known.get().name());
                peers.save(new Peer(pending.name(), peerId, known.get().displayName(), known.get().pairedAt(),
                        known.get().boxId()));
                LOG.log(Level.INFO, "Partenaire {0} appairé (contact {1} renommé)", pending.name(),
                        known.get().name());
                pending = null;
            }
            return;
        }
        if (!expected) {
            if (pending != null && pending.since().plus(pendingTimeout).isBefore(now)) {
                pending = null; // expiré ; un appairage en cours n'est pas abandonné pour une autre box
            }
            String name = PROVISIONAL_PREFIX + peerId;
            peers.save(new Peer(name, peerId, connected.displayName(), now));
            LOG.log(Level.INFO, "Connexion de {0} enregistrée comme {1}, en attente de vérification par l''annuaire",
                    connected.displayName(), name);
            return;
        }
        peers.save(new Peer(pending.name(), peerId, connected.displayName(), now));
        LOG.log(Level.INFO, "Partenaire {0} appairé (profil annoncé : {1})", pending.name(), connected.displayName());
        pending = null;
    }
}
