package com.imagunity.directory;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Objects;

/**
 * Paire de clés de chiffrement hybride (suite {@code IU1}) : X25519 et ML-KEM-768. Elle reste dans la box ; sa clé
 * publique est publiée dans l'entrée d'annuaire de la box.
 *
 * @param x25519   paire X25519
 * @param mlKem768 paire ML-KEM-768
 */
public record HybridKemKeyPair(KeyPair x25519, KeyPair mlKem768) {

    public HybridKemKeyPair {
        Objects.requireNonNull(x25519, "x25519");
        Objects.requireNonNull(mlKem768, "mlKem768");
    }

    /** Nouvelle paire, tirée par le générateur d'aléa du JDK. */
    public static HybridKemKeyPair generate() {
        try {
            return new HybridKemKeyPair(KeyPairGenerator.getInstance(Suite.X25519).generateKeyPair(),
                    KeyPairGenerator.getInstance(Suite.ML_KEM_768).generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Algorithmes de chiffrement de la suite IU1 absents du JDK", e);
        }
    }

    public HybridKemPublicKey publicKey() {
        return new HybridKemPublicKey(x25519.getPublic(), mlKem768.getPublic());
    }
}
