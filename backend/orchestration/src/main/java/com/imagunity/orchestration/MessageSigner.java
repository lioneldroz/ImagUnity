package com.imagunity.orchestration;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridSignature;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Messages de signalisation signés (annuaire activé) : enveloppe {@code version 2}.
 *
 * <pre>
 * {"type": "imagunity.transfer.announce", "version": 2, "boxId": "…", "payload": "{…corps JSON…}",
 *  "signature": {"suite": "IU1", "ed25519": "…", "mlDsa65": "…"}}
 * </pre>
 * La signature de la box émettrice porte sur « IU1-msg », le type et le texte exact du corps. Le destinataire
 * n'accepte le message que si {@code boxId} est la box à laquelle ce contact est lié et si la signature est valide
 * avec la clé de cette box publiée dans l'annuaire : non-répudiation des annonces et des accusés. Un message non signé
 * ({@code version 1}) est refusé.
 */
public final class MessageSigner {

    private static final System.Logger LOG = System.getLogger(MessageSigner.class.getName());
    static final int VERSION = 2;

    private final BoxIdentity identity;
    private final LocalDirectory directory;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public MessageSigner(BoxIdentity identity, LocalDirectory directory) {
        this.identity = identity;
        this.directory = directory;
    }

    /** Message signé portant ce corps (texte JSON exact). */
    public String seal(String type, String payload) {
        HybridSignature signature = identity.keys().sign(signedBytes(type, payload));
        ObjectNode envelope = mapper.createObjectNode().put("type", type).put("version", VERSION)
                .put("boxId", identity.boxId().toString()).put("payload", payload);
        envelope.set("signature", mapper.valueToTree(signature));
        return mapper.writeValueAsString(envelope);
    }

    /**
     * Corps d'un message de ce type reçu de ce partenaire, si sa signature est vérifiée ; vide sinon (autre type,
     * illisible, non signé, autre box, signature invalide : refus journalisé).
     */
    public Optional<String> open(Peer from, String type, String text) {
        JsonNode envelope;
        try {
            envelope = mapper.readTree(text);
        } catch (JacksonException e) {
            return Optional.empty();
        }
        if (!type.equals(envelope.path("type").asString())) {
            return Optional.empty();
        }
        if (envelope.path("version").asInt() != VERSION) {
            return refuse(from, type, "message non signé");
        }
        String payload = envelope.path("payload").asString();
        UUID sender;
        HybridSignature signature;
        try {
            sender = UUID.fromString(envelope.path("boxId").asString());
            signature = mapper.treeToValue(envelope.path("signature"), HybridSignature.class);
        } catch (IllegalArgumentException | JacksonException e) {
            return refuse(from, type, "enveloppe mal formée");
        }
        if (!sender.equals(from.boxId())) {
            return refuse(from, type, "signé par une autre box que celle de ce partenaire");
        }
        Optional<BoxEntry> entry = directory.entry(sender);
        if (entry.isEmpty() || !HybridKeyPair.verify(entry.get().signingKey(), signedBytes(type, payload), signature)) {
            return refuse(from, type, "signature invalide");
        }
        return Optional.of(payload);
    }

    private static Optional<String> refuse(Peer from, String type, String reason) {
        LOG.log(Level.WARNING, "Message {0} de {1} refusé : {2}", type, from.name(), reason);
        return Optional.empty();
    }

    static byte[] signedBytes(String type, String payload) {
        return ("IU1-msg\n" + type + "\n" + payload).getBytes(StandardCharsets.UTF_8);
    }
}
