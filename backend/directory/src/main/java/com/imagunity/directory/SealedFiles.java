package com.imagunity.directory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.DecapsulateException;
import javax.crypto.KDF;
import javax.crypto.KEM;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.HKDFParameterSpec;

/**
 * Fichiers chiffrés pour une box destinataire (suite {@code IU1}) : seule la box qui détient les clés privées de
 * chiffrement de son entrée d'annuaire peut les ouvrir ; les relais, et quiconque copie le fichier, n'y lisent rien.
 *
 * <p>Pour chaque fichier, une clé neuve est convenue avec la clé publique du destinataire par deux KEM : DHKEM
 * X25519 (RFC 9180) et ML-KEM-768 (FIPS 203). Les deux secrets sont combinés par HKDF-SHA256, avec l'en-tête du
 * fichier (destinataire, nom, encapsulations) en contexte : la clé tient tant que l'un des deux algorithmes tient.
 *
 * <p>Format :
 * <pre>
 * "IU1E" | version (1 octet) | longueur de l'en-tête (4 octets) | en-tête JSON
 * bloc 0 | bloc 1 | … | dernier bloc      (chaque bloc : AES-256-GCM, étiquette de 16 octets)
 * </pre>
 * Les blocs font {@code chunkSize} octets en clair, sauf le dernier (éventuellement vide). Chaque bloc a pour nonce
 * son rang, et pour données associées l'empreinte de l'en-tête et un indicateur « dernier bloc » : un bloc altéré,
 * retiré, déplacé, ou un fichier tronqué ou allongé est refusé. Le fichier se chiffre et se déchiffre au fil de
 * l'eau, sans être chargé en mémoire.
 *
 * <p>Ce chiffrement apporte la confidentialité ; l'authenticité de l'émetteur vient de l'annonce signée, qui porte
 * l'empreinte SHA-256 de chaque fichier chiffré.
 */
public final class SealedFiles {

    /** Extension des fichiers chiffrés. */
    public static final String EXTENSION = ".iu1";
    static final int VERSION = 1;
    static final int CHUNK_SIZE = 1 << 20;
    static final int MIN_CHUNK = 1 << 10;
    static final int MAX_CHUNK = 1 << 24;
    static final int MAX_HEADER = 16 * 1024;
    private static final byte[] MAGIC = "IU1E".getBytes(StandardCharsets.US_ASCII);
    private static final int TAG_BYTES = 16;
    private static final String CONTEXT = "IU1-paquet\n";

    private SealedFiles() {
    }

    /**
     * En-tête d'un fichier chiffré.
     *
     * @param suite     {@code IU1}
     * @param recipient empreinte de la clé de chiffrement du destinataire
     * @param name      nom du fichier chiffré, vérifié à l'ouverture
     * @param x25519    encapsulation DHKEM X25519 (base64)
     * @param mlKem768  encapsulation ML-KEM-768 (base64)
     * @param chunkSize taille des blocs en clair
     */
    record Header(String suite, String recipient, String name, String x25519, String mlKem768, int chunkSize) {
    }

    /** Vrai si ce fichier commence comme un fichier chiffré {@code IU1}. */
    public static boolean isSealed(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return Arrays.equals(in.readNBytes(MAGIC.length), MAGIC);
        }
    }

    /**
     * Chiffre {@code plain} pour ce destinataire dans un nouveau fichier {@code sealed}.
     *
     * @param name nom sous lequel le destinataire recevra le fichier (lié au chiffré)
     */
    public static void seal(Path plain, Path sealed, HybridKemPublicKey recipient, String name) throws IOException {
        seal(plain, sealed, recipient, name, CHUNK_SIZE);
    }

    static void seal(Path plain, Path sealed, HybridKemPublicKey recipient, String name, int chunkSize)
            throws IOException {
        long size = Files.size(plain);
        long chunks = Math.max(1, (size + chunkSize - 1) / chunkSize);
        // Créé avant tout : un fichier existant n'est ni écrasé, ni supprimé en cas d'échec.
        OutputStream created = Files.newOutputStream(sealed, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        boolean done = false;
        try (created; InputStream in = new BufferedInputStream(Files.newInputStream(plain));
             OutputStream out = new BufferedOutputStream(created)) {
            KEM.Encapsulated x25519 = KEM.getInstance("DHKEM").newEncapsulator(recipient.x25519()).encapsulate();
            KEM.Encapsulated mlKem = KEM.getInstance("ML-KEM").newEncapsulator(recipient.mlKem768()).encapsulate();
            Header header = new Header(Suite.ID, recipient.fingerprint(), name,
                    Base64.getEncoder().encodeToString(x25519.encapsulation()),
                    Base64.getEncoder().encodeToString(mlKem.encapsulation()), chunkSize);
            byte[] prefix = prefix(Json.write(header).getBytes(StandardCharsets.UTF_8));
            byte[] headerDigest = sha256(prefix);
            SecretKey key = derive(mlKem.key(), x25519.key(), headerDigest);
            out.write(prefix);
            for (long i = 0; i < chunks; i++) {
                int length = (int) Math.min(chunkSize, size - i * chunkSize);
                byte[] chunk = in.readNBytes(length);
                boolean last = i == chunks - 1;
                if (chunk.length != length || last && in.read() != -1) {
                    throw new IOException("Fichier modifié pendant le chiffrement : " + plain);
                }
                out.write(gcm(Cipher.ENCRYPT_MODE, key, i, headerDigest, last, chunk));
            }
            done = true;
        } catch (GeneralSecurityException e) {
            throw new DirectoryException("Chiffrement impossible : " + e.getMessage(), e);
        } finally {
            if (!done) {
                Files.deleteIfExists(sealed);
            }
        }
    }

    /**
     * Déchiffre {@code sealed} dans un nouveau fichier {@code plain}, avec les clés de cette box.
     *
     * @param name nom attendu du fichier chiffré
     * @throws DirectoryException si le fichier n'est pas destiné à cette box, porte un autre nom, est altéré ou tronqué
     */
    public static void open(Path sealed, Path plain, HybridKemKeyPair keys, String name) throws IOException {
        long size = Files.size(sealed);
        // Créé avant tout : un fichier existant n'est ni écrasé, ni supprimé en cas d'échec.
        OutputStream created = Files.newOutputStream(plain, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        boolean done = false;
        try (created;
             DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(sealed)))) {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new DirectoryException("Fichier non chiffré par ImagUnity");
            }
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new DirectoryException("Version de chiffrement non prise en charge : " + version);
            }
            int headerLength = in.readInt();
            if (headerLength <= 0 || headerLength > MAX_HEADER) {
                throw new DirectoryException("En-tête de fichier chiffré invalide");
            }
            byte[] headerBytes = in.readNBytes(headerLength);
            if (headerBytes.length != headerLength) {
                throw new DirectoryException("Fichier chiffré tronqué");
            }
            Header header = Json.read(new String(headerBytes, StandardCharsets.UTF_8), Header.class,
                    "En-tête de fichier chiffré");
            Suite.require(header.suite());
            if (!keys.publicKey().fingerprint().equals(header.recipient())) {
                throw new DirectoryException("Fichier chiffré pour une autre box");
            }
            if (!name.equals(header.name())) {
                throw new DirectoryException("Fichier chiffré sous un autre nom (" + header.name() + ")");
            }
            int chunkSize = header.chunkSize();
            if (chunkSize < MIN_CHUNK || chunkSize > MAX_CHUNK) {
                throw new DirectoryException("Taille de bloc invalide : " + chunkSize);
            }
            byte[] headerDigest = sha256(prefix(headerBytes));
            SecretKey key = derive(decapsulate("ML-KEM", keys.mlKem768(), header.mlKem768()),
                    decapsulate("DHKEM", keys.x25519(), header.x25519()), headerDigest);
            long payload = size - (MAGIC.length + 1 + 4 + headerLength);
            long full = (long) chunkSize + TAG_BYTES;
            long chunks = Math.max(1, (payload + full - 1) / full);
            long last = payload - (chunks - 1) * full;
            if (last < TAG_BYTES) {
                throw new DirectoryException("Fichier chiffré tronqué");
            }
            try (OutputStream out = new BufferedOutputStream(created)) {
                for (long i = 0; i < chunks; i++) {
                    int length = (int) (i == chunks - 1 ? last : full);
                    byte[] chunk = new byte[length];
                    in.readFully(chunk);
                    out.write(gcm(Cipher.DECRYPT_MODE, key, i, headerDigest, i == chunks - 1, chunk));
                }
                done = true;
            }
        } catch (AEADBadTagException e) {
            throw new DirectoryException("Fichier chiffré altéré, ou clé de chiffrement différente", e);
        } catch (EOFException e) {
            throw new DirectoryException("Fichier chiffré tronqué", e);
        } catch (GeneralSecurityException e) {
            throw new DirectoryException("Déchiffrement impossible : " + e.getMessage(), e);
        } finally {
            if (!done) {
                Files.deleteIfExists(plain);
            }
        }
    }

    private static SecretKey decapsulate(String algorithm, java.security.KeyPair pair, String encapsulation)
            throws GeneralSecurityException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encapsulation == null ? "" : encapsulation);
        } catch (IllegalArgumentException e) {
            throw new DirectoryException("Encapsulation " + algorithm + " illisible", e);
        }
        try {
            return KEM.getInstance(algorithm).newDecapsulator(pair.getPrivate()).decapsulate(bytes);
        } catch (DecapsulateException e) {
            throw new DirectoryException("Encapsulation " + algorithm + " invalide", e);
        }
    }

    /** Clé AES-256 : HKDF-SHA256 des deux secrets, avec l'empreinte de l'en-tête en contexte. */
    private static SecretKey derive(SecretKey mlKem, SecretKey x25519, byte[] headerDigest)
            throws GeneralSecurityException {
        byte[] context = ByteBuffer.allocate(CONTEXT.length() + headerDigest.length)
                .put(CONTEXT.getBytes(StandardCharsets.US_ASCII)).put(headerDigest).array();
        return KDF.getInstance("HKDF-SHA256").deriveKey("AES",
                HKDFParameterSpec.ofExtract().addIKM(mlKem).addIKM(x25519).thenExpand(context, 32));
    }

    private static byte[] gcm(int mode, SecretKey key, long index, byte[] headerDigest, boolean last, byte[] data)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BYTES * 8, ByteBuffer.allocate(12).putLong(4, index).array()));
        cipher.updateAAD(ByteBuffer.allocate(headerDigest.length + 1).put(headerDigest).put((byte) (last ? 1 : 0))
                .array());
        return cipher.doFinal(data);
    }

    private static byte[] prefix(byte[] header) {
        return ByteBuffer.allocate(MAGIC.length + 1 + 4 + header.length).put(MAGIC).put((byte) VERSION)
                .putInt(header.length).put(header).array();
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
