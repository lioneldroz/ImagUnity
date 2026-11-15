package com.imagunity.orchestration;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import java.util.Optional;
import java.util.UUID;

/**
 * Avec l'annuaire : un partenaire n'est accepté que s'il est lié à une box enrôlée (poignée de main vérifiée) dont
 * l'entrée est active, c'est-à-dire ni révoquée, ni expirée, ni pas encore valide. Pour une box d'une organisation
 * partenaire (fédération), il faut en plus un accord actif qui couvre les deux box (périmètre, relais). Dans tous les
 * cas, l'annuaire de l'organisation de la box ne doit pas être figé (preuve de fraîcheur trop ancienne).
 */
public final class DirectoryPeerAuthorization implements PeerAuthorization {

    private final LocalDirectory directory;
    private final UUID self;

    public DirectoryPeerAuthorization(LocalDirectory directory) {
        this(directory, null);
    }

    /** @param self cette box : sert au contrôle du périmètre des accords avec les partenaires */
    public DirectoryPeerAuthorization(LocalDirectory directory, UUID self) {
        this.directory = directory;
        this.self = self;
    }

    @Override
    public Optional<String> refusal(Peer peer) {
        if (peer.boxId() == null) {
            return Optional.of("Partenaire « " + peer.name() + " » pas encore vérifié par l'annuaire");
        }
        BoxEntry.Status status = directory.status(peer.boxId());
        // Accord, périmètre et relais (box d'un partenaire) ; entrée en retard sur la preuve de fraîcheur, annuaire figé.
        // Versions du protocole : aucune en commun, refus.
        Optional<String> federation = self == null ? directory.protocolRefusal(peer.boxId())
                : directory.boxRefusal(self, peer.boxId());
        if (status == null) {
            return Optional.of(federation.map(m -> "Partenaire « " + peer.name() + " » : " + m)
                    .orElse("Box du partenaire « " + peer.name() + " » absente de l'annuaire"));
        }
        if (status == BoxEntry.Status.ACTIVE && federation.isPresent()) {
            return Optional.of("Partenaire « " + peer.name() + " » : " + federation.get());
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
