package com.imagunity.orchestration;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import java.util.Optional;

/**
 * Avec l'annuaire : un partenaire n'est accepté que s'il est lié à une box enrôlée (poignée de main vérifiée) dont
 * l'entrée est active, c'est-à-dire ni révoquée, ni expirée, ni pas encore valide.
 */
public final class DirectoryPeerAuthorization implements PeerAuthorization {

    private final LocalDirectory directory;

    public DirectoryPeerAuthorization(LocalDirectory directory) {
        this.directory = directory;
    }

    @Override
    public Optional<String> refusal(Peer peer) {
        if (peer.boxId() == null) {
            return Optional.of("Partenaire « " + peer.name() + " » pas encore vérifié par l'annuaire");
        }
        BoxEntry.Status status = directory.status(peer.boxId());
        if (status == null) {
            return Optional.of("Box du partenaire « " + peer.name() + " » absente de l'annuaire");
        }
        return switch (status) {
            case ACTIVE -> Optional.empty();
            case REVOKED -> Optional.of("Box du partenaire « " + peer.name() + " » révoquée dans l'annuaire");
            case EXPIRED -> Optional.of("Entrée d'annuaire du partenaire « " + peer.name() + " » expirée");
            case NOT_YET_VALID -> Optional.of("Entrée d'annuaire du partenaire « " + peer.name()
                    + " » pas encore valide");
        };
    }
}
