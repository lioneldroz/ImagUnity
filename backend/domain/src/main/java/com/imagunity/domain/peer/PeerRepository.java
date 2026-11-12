package com.imagunity.domain.peer;

import java.util.List;
import java.util.Optional;

/** Persistance des partenaires appairés (port ; implémenté par le module persistence). */
public interface PeerRepository {

    /**
     * Enregistre le partenaire. Un même nom ou un même identifiant de transport déjà présent est remplacé
     * (ré-appairage).
     */
    void save(Peer peer);

    Optional<Peer> findByName(String name);

    Optional<Peer> findByTransportPeerId(long transportPeerId);

    /** Partenaires, par nom. */
    List<Peer> findAll();

    /** Oublie ce partenaire (contact provisoire jamais vérifié, contact en double). */
    void delete(String name);
}
