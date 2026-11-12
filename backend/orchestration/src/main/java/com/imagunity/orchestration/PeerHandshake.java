package com.imagunity.orchestration;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridSignature;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Poignée de main qui lie un partenaire appairé à une box enrôlée dans l'annuaire, par défi-réponse signé.
 *
 * <pre>
 * A → B : hello {boxId: A, challenge: cA}
 * B → A : proof {boxId: B, challenge: cA, signature de B sur « IU1-hello, cA, B, A »}
 * </pre>
 * A vérifie que {@code cA} est le défi qu'il a envoyé à ce contact, que B est actif dans l'annuaire et que la
 * signature est valide avec la clé de B publiée dans l'annuaire : le contact est alors lié à B. La preuve couvre le
 * défi frais de A et les deux identifiants ; elle ne peut pas être rejouée par une autre box. Chaque côté fait la même
 * chose de son côté. Une box inconnue de l'annuaire au moment de la preuve est retentée après chaque synchronisation
 * ({@link #greetUnbound()}).
 */
public final class PeerHandshake {

    private static final System.Logger LOG = System.getLogger(PeerHandshake.class.getName());
    static final String HELLO = "imagunity.peer.hello";
    static final String PROOF = "imagunity.peer.proof";
    static final int VERSION = 1;

    private final PeerRepository peers;
    private final TransportPort transport;
    private final BoxIdentity identity;
    private final LocalDirectory directory;
    private final Executor executor;
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final SecureRandom random = new SecureRandom();
    private final Map<Long, String> challenges = new ConcurrentHashMap<>();

    public PeerHandshake(PeerRepository peers, TransportPort transport, BoxIdentity identity, LocalDirectory directory,
                         Executor executor) {
        this.peers = peers;
        this.transport = transport;
        this.identity = identity;
        this.directory = directory;
        this.executor = executor;
    }

    /** S'abonne au transport et salue les partenaires pas encore liés. */
    public void start() {
        transport.addListener(event -> executor.execute(() -> onEvent(event)));
        executor.execute(this::greetUnbound);
    }

    /** Envoie un défi à chaque partenaire pas encore lié (au démarrage, puis après chaque synchronisation). */
    public void greetUnbound() {
        peers.findAll().stream().filter(p -> p.boxId() == null).forEach(this::sendHello);
    }

    void onEvent(TransportEvent event) {
        switch (event) {
            case TransportEvent.PeerConnected connected -> peers.findByTransportPeerId(connected.peer().value())
                    .filter(p -> p.boxId() == null).ifPresent(this::sendHello);
            case TransportEvent.MessageReceived message -> onMessage(message);
            default -> {
                // fichiers : sans objet
            }
        }
    }

    private void onMessage(TransportEvent.MessageReceived message) {
        JsonNode node;
        try {
            node = mapper.readTree(message.text());
        } catch (JacksonException e) {
            return;
        }
        String type = node.path("type").asString();
        if ((!HELLO.equals(type) && !PROOF.equals(type)) || node.path("version").asInt() != VERSION) {
            return;
        }
        Optional<Peer> peer = peers.findByTransportPeerId(message.peer().value());
        if (peer.isEmpty()) {
            LOG.log(Level.WARNING, "Poignée de main d''un contact non appairé : ignorée");
            return;
        }
        try {
            UUID boxId = UUID.fromString(node.path("boxId").asString());
            String challenge = node.path("challenge").asString();
            if (HELLO.equals(type)) {
                onHello(peer.get(), boxId, challenge);
            } else {
                onProof(peer.get(), boxId, challenge, mapper.treeToValue(node.path("signature"), HybridSignature.class));
            }
        } catch (IllegalArgumentException | JacksonException e) {
            LOG.log(Level.WARNING, "Poignée de main mal formée de {0} : ignorée", peer.get().name());
        }
    }

    private void onHello(Peer peer, UUID peerBox, String challenge) {
        HybridSignature signature = identity.keys().sign(signedText(challenge, identity.boxId(), peerBox));
        ObjectNode proof = envelope(PROOF).put("challenge", challenge);
        proof.set("signature", mapper.valueToTree(signature));
        send(peer, proof);
        if (peer.boxId() == null && !challenges.containsKey(peer.transportPeerId())) {
            sendHello(peer);
        }
    }

    private void onProof(Peer peer, UUID peerBox, String challenge, HybridSignature signature) {
        String expected = challenges.get(peer.transportPeerId());
        if (expected == null || !expected.equals(challenge)) {
            LOG.log(Level.WARNING, "Preuve de {0} sans défi correspondant : ignorée", peer.name());
            return;
        }
        Optional<BoxEntry> entry = directory.entry(peerBox);
        if (entry.isEmpty() || directory.status(peerBox) != BoxEntry.Status.ACTIVE) {
            LOG.log(Level.WARNING, "Partenaire {0} : box {1} absente ou inactive dans l''annuaire", peer.name(),
                    peerBox);
            return;
        }
        if (!HybridKeyPair.verify(entry.get().signingKey(), signedText(challenge, peerBox, identity.boxId()),
                signature)) {
            LOG.log(Level.WARNING, "Partenaire {0} : signature de la poignée de main invalide", peer.name());
            return;
        }
        if (peer.boxId() != null && !peer.boxId().equals(peerBox)) {
            LOG.log(Level.WARNING, "Partenaire {0} déjà lié à la box {1} : preuve pour {2} refusée", peer.name(),
                    peer.boxId(), peerBox);
            return;
        }
        challenges.remove(peer.transportPeerId());
        peers.save(peer.boundTo(peerBox));
        LOG.log(Level.INFO, "Partenaire {0} vérifié : box « {1} » ({2})", peer.name(), entry.get().displayName(),
                peerBox);
    }

    private void sendHello(Peer peer) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String challenge = Base64.getEncoder().encodeToString(bytes);
        challenges.put(peer.transportPeerId(), challenge);
        send(peer, envelope(HELLO).put("challenge", challenge));
    }

    private ObjectNode envelope(String type) {
        return mapper.createObjectNode().put("type", type).put("version", VERSION)
                .put("boxId", identity.boxId().toString());
    }

    private void send(Peer peer, ObjectNode message) {
        try {
            transport.sendMessage(new PeerId(peer.transportPeerId()), mapper.writeValueAsString(message));
        } catch (TransportException e) {
            LOG.log(Level.WARNING, "Poignée de main vers {0} non envoyée : {1}", peer.name(), e.getMessage());
        }
    }

    /** Texte signé par la box qui prouve son identité : le défi reçu, son identifiant, celui du demandeur. */
    static byte[] signedText(String challenge, UUID prover, UUID verifier) {
        return ("IU1-hello\n" + challenge + "\n" + prover + "\n" + verifier).getBytes(StandardCharsets.UTF_8);
    }
}
