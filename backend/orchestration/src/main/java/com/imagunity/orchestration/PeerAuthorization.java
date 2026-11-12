package com.imagunity.orchestration;

import com.imagunity.domain.peer.Peer;
import java.util.Optional;

/** Décide si l'on peut échanger avec un partenaire appairé : sans annuaire, toujours ; avec, selon son entrée. */
@FunctionalInterface
public interface PeerAuthorization {

    /** Motif du refus, ou vide si l'échange est permis. */
    Optional<String> refusal(Peer peer);

    /** Sans annuaire : tout partenaire appairé est accepté. */
    static PeerAuthorization allowAll() {
        return peer -> Optional.empty();
    }
}
