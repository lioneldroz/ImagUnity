package com.imagunity.orchestration;

import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.transport.Invitation;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportPort;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Appairage des box pour la preuve de concept, sans annuaire : par lien d'invitation à usage unique.
 *
 * <ol>
 *   <li>La box A crée une invitation pour le partenaire qu'elle nommera {@code box-b} ;</li>
 *   <li>le lien est transmis hors bande (QR code, code court, messagerie de confiance) ;</li>
 *   <li>la box B le rejoint en nommant ce partenaire {@code box-a} ;</li>
 *   <li>à la connexion, chaque box enregistre le partenaire sous le nom qu'<b>elle</b> a choisi.</li>
 * </ol>
 * Le nom vient de l'opérateur local, jamais du profil annoncé par le partenaire. Un seul appairage
 * peut être en cours à la fois ; il expire après le délai configuré.
 */
public final class PairingService {

    private static final System.Logger LOG = System.getLogger(PairingService.class.getName());

    private record Pending(String name, Instant since) {
    }

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

    /** Crée une invitation pour un partenaire qui sera enregistré sous {@code name}. */
    public synchronized Invitation invite(String name) {
        Peer.requireValidName(name);
        Invitation invitation = transport.createInvitation();
        pending = new Pending(name, clock.instant());
        LOG.log(Level.INFO, "Invitation créée pour le partenaire {0}", name);
        return invitation;
    }

    /** Rejoint le partenaire qui a émis {@code link}, enregistré localement sous {@code name}. */
    public synchronized void join(String name, String link) {
        Peer.requireValidName(name);
        // Avant l'appel : la connexion peut être signalée avant son retour.
        pending = new Pending(name, clock.instant());
        try {
            transport.acceptInvitation(link);
        } catch (RuntimeException e) {
            pending = null;
            throw e;
        }
        LOG.log(Level.INFO, "Invitation acceptée pour le partenaire {0}", name);
    }

    public List<Peer> peers() {
        return peers.findAll();
    }

    synchronized void onTransportEvent(TransportEvent event) {
        if (!(event instanceof TransportEvent.PeerConnected connected)) {
            return;
        }
        long peerId = connected.peer().value();
        if (peers.findByTransportPeerId(peerId).isPresent()) {
            return; // partenaire déjà appairé
        }
        Instant now = clock.instant();
        if (pending == null || pending.since().plus(pendingTimeout).isBefore(now)) {
            pending = null;
            LOG.log(Level.WARNING, "Connexion d''un partenaire non attendu ({0}) : ignorée", connected.displayName());
            return;
        }
        peers.save(new Peer(pending.name(), peerId, connected.displayName(), now));
        LOG.log(Level.INFO, "Partenaire {0} appairé (profil annoncé : {1})", pending.name(), connected.displayName());
        pending = null;
    }
}
