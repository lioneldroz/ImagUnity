package com.imagunity.directory;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;

/**
 * Paire de clés hybride (suite {@code IU1}) : signe en Ed25519 et en ML-DSA-65.
 *
 * @param ed25519 paire Ed25519
 * @param mlDsa65 paire ML-DSA-65
 */
public record HybridKeyPair(KeyPair ed25519, KeyPair mlDsa65) {

    public HybridKeyPair {
        Objects.requireNonNull(ed25519, "ed25519");
        Objects.requireNonNull(mlDsa65, "mlDsa65");
    }

    /** Nouvelle paire, tirée par le générateur d'aléa du JDK. */
    public static HybridKeyPair generate() {
        try {
            return new HybridKeyPair(KeyPairGenerator.getInstance(Suite.ED25519).generateKeyPair(),
                    KeyPairGenerator.getInstance(Suite.ML_DSA_65).generateKeyPair());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Algorithmes de la suite IU1 absents du JDK", e);
        }
    }

    public HybridPublicKey publicKey() {
        return new HybridPublicKey(ed25519.getPublic(), mlDsa65.getPublic());
    }

    /** Signe ces octets avec les deux clés. */
    public HybridSignature sign(byte[] data) {
        return new HybridSignature(Suite.ID, sign(Suite.ED25519, ed25519, data), sign(Suite.ML_DSA_65, mlDsa65, data));
    }

    /** Vrai si les deux signatures sont valides pour ces octets et cette clé publique. */
    public static boolean verify(HybridPublicKey key, byte[] data, HybridSignature signature) {
        if (signature == null || !Suite.ID.equals(signature.suite())) {
            return false;
        }
        return verify(Suite.ED25519, key.ed25519(), data, signature.ed25519())
                && verify(Suite.ML_DSA_65, key.mlDsa65(), data, signature.mlDsa65());
    }

    private static String sign(String algorithm, KeyPair pair, byte[] data) {
        try {
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(pair.getPrivate());
            signer.update(data);
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signature " + algorithm + " impossible", e);
        }
    }

    private static boolean verify(String algorithm, PublicKey key, byte[] data, String base64) {
        if (base64 == null) {
            return false;
        }
        try {
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(key);
            verifier.update(data);
            return verifier.verify(Base64.getDecoder().decode(base64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
