package com.imagunity.orchestration;

import com.imagunity.directory.HybridKeyPair;
import java.util.Objects;
import java.util.UUID;

/**
 * Identité de cette box dans l'annuaire : son identifiant et sa paire de clés de signature, générées dans la box.
 *
 * @param boxId identifiant de la box
 * @param keys  clés de signature (suite {@code IU1})
 */
public record BoxIdentity(UUID boxId, HybridKeyPair keys) {

    public BoxIdentity {
        Objects.requireNonNull(boxId, "boxId");
        Objects.requireNonNull(keys, "keys");
    }
}
