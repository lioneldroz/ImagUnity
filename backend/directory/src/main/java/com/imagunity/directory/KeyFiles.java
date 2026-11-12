package com.imagunity.directory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Fichiers de clés : clé privée hybride (PKCS#8, droits 600, jamais écrasée) et clé publique d'organisation.
 *
 * <p>La clé privée est chiffrée par phrase de passe ({@link KeyProtection}) ; une clé en clair reste lisible (fichiers
 * antérieurs) et peut être chiffrée sur place ({@link #protect}). Le support matériel (HSM, TPM) attend que ces
 * équipements prennent en charge ML-DSA.
 */
public final class KeyFiles {

    private KeyFiles() {
    }

    /**
     * Clé privée enregistrée : en clair ({@code ed25519Private}, {@code mlDsa65Private}) ou chiffrée par phrase de
     * passe ({@code protection}, champs en clair absents).
     */
    record PrivateKeyFile(String suite, HybridPublicKey.Encoded publicKey, String ed25519Private,
                          String mlDsa65Private, KeyProtection.Sealed protection) {
    }

    /** Parties privées, chiffrées ensemble quand la clé est protégée. */
    record PrivateParts(String ed25519Private, String mlDsa65Private) {
    }

    /** Usage inscrit dans un fichier de clés de chiffrement. */
    static final String ENCRYPTION_USAGE = "chiffrement";

    /**
     * Clés privées de chiffrement enregistrées : en clair ({@code x25519Private}, {@code mlKem768Private}) ou chiffrées
     * par phrase de passe ({@code protection}).
     */
    record EncryptionKeyFile(String suite, String usage, HybridKemPublicKey.Encoded publicKey, String x25519Private,
                             String mlKem768Private, KeyProtection.Sealed protection) {
    }

    /** Parties privées de chiffrement, chiffrées ensemble quand la clé est protégée. */
    record EncryptionParts(String x25519Private, String mlKem768Private) {
    }

    /**
     * Clé publique d'une organisation, à installer dans les box après vérification de son empreinte courte.
     *
     * @param suite        {@code IU1}
     * @param organization organisation
     * @param signingKeys  clé publique du responsable
     * @param shortCode    empreinte courte (rappel ; recalculée à la lecture)
     */
    public record OrganizationPublicKey(String suite, String organization, HybridPublicKey.Encoded signingKeys,
                                        String shortCode) {

        public HybridPublicKey key() {
            return HybridPublicKey.decode(signingKeys);
        }
    }

    /**
     * Enregistre une clé privée dans un nouveau fichier, lisible par son seul propriétaire.
     *
     * @throws FileAlreadyExistsException si le fichier existe : une clé n'est jamais écrasée
     */
    public static void writePrivate(Path file, HybridKeyPair keys) throws IOException {
        writePrivate(file, keys, null);
    }

    /**
     * Enregistre une clé privée dans un nouveau fichier, lisible par son seul propriétaire, chiffrée par cette phrase
     * de passe ({@code null} : en clair, déconseillé).
     *
     * @throws FileAlreadyExistsException si le fichier existe : une clé n'est jamais écrasée
     * @throws IllegalArgumentException   si la phrase de passe est trop courte
     */
    public static void writePrivate(Path file, HybridKeyPair keys, char[] passphrase) throws IOException {
        String content = content(keys, passphrase);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        createOwnerOnly(file);
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /** Vrai si ce fichier de clés privées (signature ou chiffrement) est chiffré par phrase de passe. */
    public static boolean isProtected(Path file) throws IOException {
        try {
            tools.jackson.databind.JsonNode protection = Json.MAPPER.readTree(Files.readString(file,
                    StandardCharsets.UTF_8)).path("protection");
            return !protection.isMissingNode() && !protection.isNull();
        } catch (tools.jackson.core.JacksonException e) {
            throw new DirectoryException("Fichier de clé privée illisible : " + e.getOriginalMessage(), e);
        }
    }

    /**
     * Chiffre par phrase de passe des clés privées (signature ou chiffrement) enregistrées en clair ; le fichier est
     * remplacé d'un coup.
     *
     * @throws DirectoryException si la clé est déjà protégée
     */
    public static void protect(Path file, char[] passphrase) throws IOException {
        if (isProtected(file)) {
            throw new DirectoryException("Clé déjà protégée par une phrase de passe : " + file);
        }
        String content = isEncryptionFile(file) ? encryptionContent(readEncryptionPrivate(file, null), passphrase)
                : content(readPrivate(file), passphrase);
        Path temporary = file.resolveSibling(file.getFileName() + ".nouveau");
        Files.deleteIfExists(temporary);
        createOwnerOnly(temporary);
        Files.writeString(temporary, content, StandardCharsets.UTF_8);
        Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static boolean isEncryptionFile(Path file) throws IOException {
        return ENCRYPTION_USAGE.equals(Json.MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8))
                .path("usage").asString(""));
    }

    /**
     * Enregistre des clés privées de chiffrement dans un nouveau fichier, lisible par son seul propriétaire, chiffré
     * par cette phrase de passe ({@code null} : en clair, déconseillé).
     *
     * @throws FileAlreadyExistsException si le fichier existe : une clé n'est jamais écrasée
     */
    public static void writeEncryptionPrivate(Path file, HybridKemKeyPair keys, char[] passphrase)
            throws IOException {
        String content = encryptionContent(keys, passphrase);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        createOwnerOnly(file);
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * Lit des clés privées de chiffrement, protégées ou non (la phrase de passe est ignorée pour des clés en clair).
     *
     * @throws DirectoryException si la phrase de passe manque ou est incorrecte, ou si le fichier est altéré
     */
    public static HybridKemKeyPair readEncryptionPrivate(Path file, char[] passphrase) throws IOException {
        EncryptionKeyFile content = Json.read(Files.readString(file, StandardCharsets.UTF_8), EncryptionKeyFile.class,
                "Fichier de clés de chiffrement");
        Suite.require(content.suite());
        if (!ENCRYPTION_USAGE.equals(content.usage())) {
            throw new DirectoryException("Fichier de clés de chiffrement d'usage inattendu : " + content.usage());
        }
        HybridKemPublicKey publicKey = HybridKemPublicKey.decode(content.publicKey());
        EncryptionParts parts;
        if (content.protection() == null) {
            parts = new EncryptionParts(content.x25519Private(), content.mlKem768Private());
        } else {
            byte[] secret = KeyProtection.open(content.protection(), passphrase, publicKey.fingerprint());
            try {
                parts = Json.read(new String(secret, StandardCharsets.UTF_8), EncryptionParts.class,
                        "Clés de chiffrement");
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        }
        return new HybridKemKeyPair(
                new KeyPair(publicKey.x25519(), privateKey(Suite.X25519, parts.x25519Private())),
                new KeyPair(publicKey.mlKem768(), privateKey(Suite.ML_KEM_768, parts.mlKem768Private())));
    }

    private static String encryptionContent(HybridKemKeyPair keys, char[] passphrase) {
        EncryptionParts parts = new EncryptionParts(base64(keys.x25519().getPrivate()),
                base64(keys.mlKem768().getPrivate()));
        if (passphrase == null) {
            return Json.write(new EncryptionKeyFile(Suite.ID, ENCRYPTION_USAGE, keys.publicKey().encoded(),
                    parts.x25519Private(), parts.mlKem768Private(), null));
        }
        byte[] secret = Json.write(parts).getBytes(StandardCharsets.UTF_8);
        try {
            KeyProtection.Sealed sealed = KeyProtection.seal(secret, passphrase, keys.publicKey().fingerprint());
            return Json.write(new EncryptionKeyFile(Suite.ID, ENCRYPTION_USAGE, keys.publicKey().encoded(), null, null,
                    sealed));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static String content(HybridKeyPair keys, char[] passphrase) {
        PrivateParts parts = new PrivateParts(base64(keys.ed25519().getPrivate()),
                base64(keys.mlDsa65().getPrivate()));
        if (passphrase == null) {
            return Json.write(new PrivateKeyFile(Suite.ID, keys.publicKey().encoded(), parts.ed25519Private(),
                    parts.mlDsa65Private(), null));
        }
        byte[] secret = Json.write(parts).getBytes(StandardCharsets.UTF_8);
        try {
            KeyProtection.Sealed sealed = KeyProtection.seal(secret, passphrase, keys.publicKey().fingerprint());
            return Json.write(new PrivateKeyFile(Suite.ID, keys.publicKey().encoded(), null, null, sealed));
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static void createOwnerOnly(Path file) throws IOException {
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(file);
        }
    }

    /** Lit une clé privée en clair ; une clé protégée demande {@link #readPrivate(Path, char[])}. */
    public static HybridKeyPair readPrivate(Path file) throws IOException {
        return readPrivate(file, null);
    }

    /**
     * Lit une clé privée, protégée ou non (la phrase de passe est ignorée pour une clé en clair).
     *
     * @throws DirectoryException si la phrase de passe manque ou est incorrecte, ou si le fichier est altéré
     */
    public static HybridKeyPair readPrivate(Path file, char[] passphrase) throws IOException {
        PrivateKeyFile content = readFile(file);
        HybridPublicKey publicKey = HybridPublicKey.decode(content.publicKey());
        PrivateParts parts;
        if (content.protection() == null) {
            parts = new PrivateParts(content.ed25519Private(), content.mlDsa65Private());
        } else {
            byte[] secret = KeyProtection.open(content.protection(), passphrase, publicKey.fingerprint());
            try {
                parts = Json.read(new String(secret, StandardCharsets.UTF_8), PrivateParts.class, "Clé privée");
            } finally {
                Arrays.fill(secret, (byte) 0);
            }
        }
        return new HybridKeyPair(
                new KeyPair(publicKey.ed25519(), privateKey(Suite.ED25519, parts.ed25519Private())),
                new KeyPair(publicKey.mlDsa65(), privateKey(Suite.ML_DSA_65, parts.mlDsa65Private())));
    }

    private static PrivateKeyFile readFile(Path file) throws IOException {
        PrivateKeyFile content = Json.read(Files.readString(file, StandardCharsets.UTF_8), PrivateKeyFile.class,
                "Fichier de clé privée");
        Suite.require(content.suite());
        return content;
    }

    public static void writeOrganizationPublic(Path file, String organization, HybridPublicKey key) throws IOException {
        Files.writeString(file, Json.write(new OrganizationPublicKey(Suite.ID, organization, key.encoded(),
                key.shortCode())), StandardCharsets.UTF_8);
    }

    public static OrganizationPublicKey readOrganizationPublic(Path file) throws IOException {
        OrganizationPublicKey key = Json.read(Files.readString(file, StandardCharsets.UTF_8),
                OrganizationPublicKey.class, "Clé publique d'organisation");
        Suite.require(key.suite());
        Organization.requireValidId(key.organization());
        return key;
    }

    private static String base64(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    private static PrivateKey privateKey(String algorithm, String base64) {
        try {
            return KeyFactory.getInstance(algorithm)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new DirectoryException("Clé privée " + algorithm + " illisible", e);
        }
    }
}
