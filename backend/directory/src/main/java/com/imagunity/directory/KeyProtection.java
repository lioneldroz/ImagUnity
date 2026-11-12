package com.imagunity.directory;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Protection d'une clé privée par phrase de passe, avec les primitives standard du JDK : dérivation
 * PBKDF2-HMAC-SHA256 (600 000 itérations, sel aléatoire de 16 octets), chiffrement authentifié AES-256-GCM (nonce de
 * 12 octets). Les données associées lient le chiffré à la clé publique : un fichier ne peut pas être échangé contre
 * celui d'une autre clé.
 */
public final class KeyProtection {

    static final String KDF = "PBKDF2-HMAC-SHA256";
    static final String CIPHER = "AES-256-GCM";
    static final int ITERATIONS = 600_000;
    /** Longueur minimale d'une phrase de passe. */
    public static final int MIN_LENGTH = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * Paramètres et chiffré, tels qu'enregistrés.
     *
     * @param kdf        {@code PBKDF2-HMAC-SHA256}
     * @param iterations nombre d'itérations
     * @param salt       sel (base64)
     * @param cipher     {@code AES-256-GCM}
     * @param nonce      nonce (base64)
     * @param ciphertext chiffré et étiquette (base64)
     */
    public record Sealed(String kdf, int iterations, String salt, String cipher, String nonce, String ciphertext) {
    }

    private KeyProtection() {
    }

    /** Refuse une phrase de passe trop courte. */
    public static void requireStrong(char[] passphrase) {
        if (passphrase == null || passphrase.length < MIN_LENGTH) {
            throw new IllegalArgumentException("Phrase de passe trop courte : " + MIN_LENGTH + " caractères au moins");
        }
    }

    static Sealed seal(byte[] secret, char[] passphrase, String binding) {
        requireStrong(passphrase);
        byte[] salt = random(16);
        byte[] nonce = random(12);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, ITERATIONS, nonce, binding);
            return new Sealed(KDF, ITERATIONS, b64(salt), CIPHER, b64(nonce), b64(cipher.doFinal(secret)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Chiffrement de la clé impossible", e);
        }
    }

    static byte[] open(Sealed sealed, char[] passphrase, String binding) {
        if (!KDF.equals(sealed.kdf()) || !CIPHER.equals(sealed.cipher()) || sealed.iterations() < 100_000) {
            throw new DirectoryException("Protection de clé non prise en charge : " + sealed.kdf() + ", "
                    + sealed.cipher() + ", " + sealed.iterations() + " itérations");
        }
        if (passphrase == null || passphrase.length == 0) {
            throw new DirectoryException("Clé protégée par une phrase de passe : phrase de passe manquante");
        }
        try {
            Base64.Decoder d = Base64.getDecoder();
            return cipher(Cipher.DECRYPT_MODE, passphrase, d.decode(sealed.salt()), sealed.iterations(),
                    d.decode(sealed.nonce()), binding).doFinal(d.decode(sealed.ciphertext()));
        } catch (AEADBadTagException e) {
            throw new DirectoryException("Phrase de passe incorrecte, ou fichier de clé altéré", e);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DirectoryException("Fichier de clé protégé illisible", e);
        }
    }

    private static Cipher cipher(int mode, char[] passphrase, byte[] salt, int iterations, byte[] nonce,
                                 String binding) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        spec.clearPassword();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(("IU1-cle\n" + binding).getBytes(StandardCharsets.UTF_8));
            return cipher;
        } finally {
            Arrays.fill(key, (byte) 0);
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
