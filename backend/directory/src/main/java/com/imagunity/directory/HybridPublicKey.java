package com.imagunity.directory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Clé publique hybride (suite {@code IU1}) : une clé Ed25519 et une clé ML-DSA-65.
 *
 * @param ed25519 clé Ed25519
 * @param mlDsa65 clé ML-DSA-65
 */
public record HybridPublicKey(PublicKey ed25519, PublicKey mlDsa65) {

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public HybridPublicKey {
        Objects.requireNonNull(ed25519, "ed25519");
        Objects.requireNonNull(mlDsa65, "mlDsa65");
    }

    /** Forme publiée : chaque clé en X.509, encodée en base64. */
    public Encoded encoded() {
        return new Encoded(base64(ed25519), base64(mlDsa65));
    }

    /** Relit une clé publiée. */
    public static HybridPublicKey decode(Encoded encoded) {
        if (encoded == null || encoded.ed25519() == null || encoded.mlDsa65() == null) {
            throw new DirectoryException("Clé publique incomplète");
        }
        return new HybridPublicKey(publicKey(Suite.ED25519, encoded.ed25519()),
                publicKey(Suite.ML_DSA_65, encoded.mlDsa65()));
    }

    /** Empreinte : SHA-256 de {@code IU1}, puis des deux clés publiées, en hexadécimal. */
    public String fingerprint() {
        return HexFormat.of().formatHex(digest());
    }

    /**
     * Empreinte courte, à comparer hors bande (téléphone, papier) : les 120 premiers bits de l'empreinte en base 32,
     * par groupes de quatre, par exemple {@code K7QF-2MZD-XA4N-P3TB-9C6W-RE2H}.
     */
    public String shortCode() {
        byte[] bits = Arrays.copyOf(digest(), 15);
        StringBuilder code = new StringBuilder();
        int buffer = 0;
        int count = 0;
        for (byte b : bits) {
            buffer = (buffer << 8) | (b & 0xff);
            count += 8;
            while (count >= 5) {
                if (!code.isEmpty() && code.length() % 5 == 4) {
                    code.append('-');
                }
                code.append(BASE32.charAt((buffer >> (count - 5)) & 31));
                count -= 5;
            }
        }
        return code.toString();
    }

    private byte[] digest() {
        Encoded e = encoded();
        String text = Suite.ID + "\n" + e.ed25519() + "\n" + e.mlDsa65();
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
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
            throw new DirectoryException("Clé publique " + algorithm + " illisible", e);
        }
    }

    /**
     * Forme publiée d'une clé hybride.
     *
     * @param ed25519 clé Ed25519 (X.509, base64)
     * @param mlDsa65 clé ML-DSA-65 (X.509, base64)
     */
    public record Encoded(String ed25519, String mlDsa65) {
    }
}
