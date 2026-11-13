package com.imagunity.transport.iu;

import com.imagunity.directory.HybridKemPublicKey;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.protocol.SignedMessage;
import java.time.Clock;
import java.util.UUID;

/**
 * Envoi de messages IU-MSG par une box : message signé par son identité, chiffré pour la box destinataire, déposé
 * dans la file publiée par celle-ci dans l'annuaire, avec nouvelles tentatives.
 */
public final class IuMessenger {

    private final IuMsgClient client;
    private final UUID boxId;
    private final HybridKeyPair identity;
    private final Clock clock;
    private final Retry retry;

    public IuMessenger(IuMsgClient client, UUID boxId, HybridKeyPair identity, Clock clock, Retry retry) {
        this.client = client;
        this.boxId = boxId;
        this.identity = identity;
        this.clock = clock;
        this.retry = retry;
    }

    /**
     * Envoie un message.
     *
     * @param to        adresse de la file du destinataire (annuaire)
     * @param toBox     box destinataire
     * @param recipient clés de chiffrement du destinataire (annuaire)
     * @return le message signé envoyé
     */
    public SignedMessage send(QueueAddress to, UUID toBox, HybridKemPublicKey recipient, String type, String body) {
        SignedMessage message = SignedMessage.of(type, boxId, toBox, body, clock.instant());
        byte[] envelope = message.seal(identity, recipient);
        retry.run("Dépôt du message " + type, () -> client.deposit(to, boxId, identity, envelope));
        return message;
    }
}
