package com.imagunity.directory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Fichiers de clés : clé privée hybride (PKCS#8, droits 600, jamais écrasée) et clé publique d'organisation.
 *
 * <p>Preuve de concept : la clé privée est protégée par les droits du fichier. Pour le pilote : phrase de passe,
 * support matériel (responsable) ou TPM (box).
 */
public final class KeyFiles {

    private KeyFiles() {
    }

    /** Clé privée enregistrée. */
    record PrivateKeyFile(String suite, HybridPublicKey.Encoded publicKey, String ed25519Private,
                          String mlDsa65Private) {
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
        PrivateKeyFile content = new PrivateKeyFile(Suite.ID, keys.publicKey().encoded(),
                base64(keys.ed25519().getPrivate()), base64(keys.mlDsa65().getPrivate()));
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(file);
        }
        Files.writeString(file, Json.write(content), StandardCharsets.UTF_8);
    }

    public static HybridKeyPair readPrivate(Path file) throws IOException {
        PrivateKeyFile content = Json.read(Files.readString(file, StandardCharsets.UTF_8), PrivateKeyFile.class,
                "Fichier de clé privée");
        Suite.require(content.suite());
        HybridPublicKey publicKey = HybridPublicKey.decode(content.publicKey());
        return new HybridKeyPair(
                new KeyPair(publicKey.ed25519(), privateKey(Suite.ED25519, content.ed25519Private())),
                new KeyPair(publicKey.mlDsa65(), privateKey(Suite.ML_DSA_65, content.mlDsa65Private())));
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
