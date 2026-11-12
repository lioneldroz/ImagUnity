package com.imagunity.orchestration;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import java.util.Objects;
import java.util.UUID;

/**
 * Identité de cette box dans l'annuaire : son identifiant, sa paire de clés de signature et sa paire de clés de
 * chiffrement, générées dans la box.
 *
 * @param boxId      identifiant de la box
 * @param keys       clés de signature (suite {@code IU1})
 * @param encryption clés de chiffrement (suite {@code IU1}) : ouvrent les paquets chiffrés pour cette box
 */
public record BoxIdentity(UUID boxId, HybridKeyPair keys, HybridKemKeyPair encryption) {

    public BoxIdentity {
        Objects.requireNonNull(boxId, "boxId");
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(encryption, "encryption");
    }

    /** Identité avec une paire de clés de chiffrement neuve. */
    public BoxIdentity(UUID boxId, HybridKeyPair keys) {
        this(boxId, keys, HybridKemKeyPair.generate());
    }
}
