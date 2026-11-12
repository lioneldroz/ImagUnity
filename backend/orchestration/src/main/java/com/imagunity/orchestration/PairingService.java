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
import java.util.concurrent.Executor;

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
 *
 * <p><b>Avec l'annuaire</b> ({@code directoryMode}), l'opérateur peut aussi se connecter directement à l'adresse de
 * contact qu'une box de l'organisation publie dans l'annuaire ({@link #join} avec cette adresse). En face, la demande
 * de contact est acceptée et la connexion enregistrée comme partenaire provisoire {@code attente-<n>} : elle n'est
 * autorisée à rien tant que la poignée de main signée ne l'a pas liée à une box active de l'annuaire, qui la nomme
 * alors (voir {@link PeerHandshake}).
 */
public final class PairingService {

    private static final System.Logger LOG = System.getLogger(PairingService.class.getName());

    private record Pending(String name, Instant since) {
    }

    private final PeerRepository peers;
    private final TransportPort transport;
    private final Clock clock;
    /** Préfixe des partenaires provisoires (connexion entrante pas encore vérifiée par l'annuaire). */
    public static final String PROVISIONAL_PREFIX = "attente-";

    private final Duration pendingTimeout;
    private final Executor executor;
    private final boolean directoryMode;
    private Pending pending;

    public PairingService(PeerRepository peers, TransportPort transport, Clock clock, Duration pendingTimeout) {
        this(peers, transport, clock, pendingTimeout, Runnable::run, false);
    }

    /**
     * @param executor      fil où accepter les demandes de contact (pas celui des événements du transport)
     * @param directoryMode accepte les connexions entrantes comme partenaires provisoires (annuaire activé)
     */
    public PairingService(PeerRepository peers, TransportPort transport, Clock clock, Duration pendingTimeout,
                          Executor executor, boolean directoryMode) {
        this.peers = peers;
        this.transport = transport;
        this.clock = clock;
        this.pendingTimeout = pendingTimeout;
        this.executor = executor;
        this.directoryMode = directoryMode;
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

    /** Avec l'annuaire, accepte la demande (la poignée de main décidera) ; sans, l'ignore. */
    private void onContactRequested(TransportEvent.ContactRequested request) {
        if (!directoryMode) {
            LOG.log(Level.WARNING, "Demande de contact de {0} ignorée (annuaire non activé)", request.displayName());
            return;
        }
        executor.execute(() -> {
            try {
                transport.acceptContactRequest(request.requestId());
                LOG.log(Level.INFO, "Demande de contact de {0} acceptée, vérification par l''annuaire à suivre",
                        request.displayName());
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Demande de contact de {0} non acceptée : {1}", request.displayName(),
                        e.getMessage());
            }
        });
    }

    synchronized void onTransportEvent(TransportEvent event) {
        if (event instanceof TransportEvent.ContactRequested request) {
            onContactRequested(request);
            return;
        }
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
            if (directoryMode) {
                String name = PROVISIONAL_PREFIX + peerId;
                peers.save(new Peer(name, peerId, connected.displayName(), now));
                LOG.log(Level.INFO, "Connexion de {0} enregistrée comme {1}, en attente de vérification par l''annuaire",
                        connected.displayName(), name);
                return;
            }
            LOG.log(Level.WARNING, "Connexion d''un partenaire non attendu ({0}) : ignorée", connected.displayName());
            return;
        }
        peers.save(new Peer(pending.name(), peerId, connected.displayName(), now));
        LOG.log(Level.INFO, "Partenaire {0} appairé (profil annoncé : {1})", pending.name(), connected.displayName());
        pending = null;
    }
}
