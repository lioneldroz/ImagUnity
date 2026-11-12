package com.imagunity.directory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Cache des entrées déjà vérifiées, côté box (en base) : la box fonctionne si l'annuaire est injoignable. */
public interface EntryCache {

    /** Remplace l'entrée en cache de cette box. */
    void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt);

    /** Entrées en cache, revérifiées au chargement. */
    List<SignedDocument> entries();
}
