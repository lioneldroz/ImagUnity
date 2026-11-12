package com.imagunity.directory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Clé publique de chiffrement hybride (suite {@code IU1}) : une clé X25519 et une clé ML-KEM-768. Un paquet chiffré
 * pour cette clé ne s'ouvre qu'avec les deux clés privées : il reste protégé tant que l'un des deux algorithmes tient.
 *
 * @param x25519   clé X25519
 * @param mlKem768 clé ML-KEM-768
 */
public record HybridKemPublicKey(PublicKey x25519, PublicKey mlKem768) {

    public HybridKemPublicKey {
        Objects.requireNonNull(x25519, "x25519");
        Objects.requireNonNull(mlKem768, "mlKem768");
    }

    /** Forme publiée : chaque clé en X.509, encodée en base64. */
    public Encoded encoded() {
        return new Encoded(base64(x25519), base64(mlKem768));
    }

    /** Relit une clé publiée. */
    public static HybridKemPublicKey decode(Encoded encoded) {
        if (encoded == null || encoded.x25519() == null || encoded.mlKem768() == null) {
            throw new DirectoryException("Clé de chiffrement incomplète");
        }
        return new HybridKemPublicKey(publicKey(Suite.X25519, encoded.x25519()),
                publicKey(Suite.ML_KEM_768, encoded.mlKem768()));
    }

    /** Empreinte : SHA-256 de {@code IU1-chiffrement}, puis des deux clés publiées, en hexadécimal. */
    public String fingerprint() {
        Encoded e = encoded();
        String text = Suite.ID + "-chiffrement\n" + e.x25519() + "\n" + e.mlKem768();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private static PublicKey publicKey(String algorithm, String base64) {
        try {
            return KeyFactory.getInstance(algorithm)
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DirectoryException("Clé de chiffrement " + algorithm + " illisible", e);
        }
    }

    /**
     * Forme publiée d'une clé de chiffrement hybride.
     *
     * @param x25519   clé X25519 (X.509, base64)
     * @param mlKem768 clé ML-KEM-768 (X.509, base64)
     */
    public record Encoded(String x25519, String mlKem768) {
    }
}
