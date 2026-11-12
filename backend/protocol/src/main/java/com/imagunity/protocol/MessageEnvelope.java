package com.imagunity.protocol;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKemPublicKey;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
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
 * Enveloppe d'un message IU-MSG (spécification § 8.4) : contenu chiffré pour la box destinataire, avec la construction
 * du chiffrement applicatif des paquets (DHKEM X25519 + ML-KEM-768, HKDF-SHA256, AES-256-GCM), appliquée à un contenu
 * en mémoire. Le contenu est complété jusqu'à un palier (4, 16 ou 64 Kio) pour limiter ce que sa taille révèle.
 *
 * <pre>
 * "IU1M" | version (1 octet) | longueur de l'en-tête (2 octets) | en-tête JSON | AES-256-GCM(longueur ‖ contenu ‖ zéros)
 * </pre>
 * La clé, neuve pour chaque message, est dérivée avec l'empreinte de l'en-tête en contexte : toute modification de
 * l'en-tête ou du chiffré est détectée.
 */
public final class MessageEnvelope {

    static final int VERSION = 1;
    /** Paliers de taille du contenu complété. */
    static final int[] TIERS = {4 * 1024, 16 * 1024, 64 * 1024};
    /** Contenu maximal (le palier le plus haut, moins la longueur). */
    public static final int MAX_CONTENT = TIERS[TIERS.length - 1] - 4;
    private static final byte[] MAGIC = "IU1M".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_HEADER = 8 * 1024;
    private static final String CONTEXT = "IU1-message\n";

    private MessageEnvelope() {
    }

    /**
     * En-tête.
     *
     * @param suite     {@code IU1}
     * @param recipient empreinte de la clé de chiffrement du destinataire
     * @param x25519    encapsulation DHKEM X25519 (base64)
     * @param mlKem768  encapsulation ML-KEM-768 (base64)
     */
    record Header(String suite, String recipient, String x25519, String mlKem768) {
    }

    /** Chiffre ce contenu pour le destinataire. */
    public static byte[] seal(byte[] content, HybridKemPublicKey recipient) {
        if (content.length > MAX_CONTENT) {
            throw new ProtocolException("Message trop long : " + content.length + " octets (" + MAX_CONTENT
                    + " au plus)");
        }
        try {
            KEM.Encapsulated x25519 = KEM.getInstance("DHKEM").newEncapsulator(recipient.x25519()).encapsulate();
            KEM.Encapsulated mlKem = KEM.getInstance("ML-KEM").newEncapsulator(recipient.mlKem768()).encapsulate();
            Header header = new Header("IU1", recipient.fingerprint(),
                    Base64.getEncoder().encodeToString(x25519.encapsulation()),
                    Base64.getEncoder().encodeToString(mlKem.encapsulation()));
            byte[] prefix = prefix(Codec.utf8(Codec.toJson(header)));
            SecretKey key = derive(mlKem.key(), x25519.key(), Codec.sha256(prefix));
            byte[] padded = new byte[tier(content.length + 4)];
            ByteBuffer.wrap(padded).putInt(content.length).put(content);
            byte[] sealed = gcm(Cipher.ENCRYPT_MODE, key, Codec.sha256(prefix), padded);
            return ByteBuffer.allocate(prefix.length + sealed.length).put(prefix).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("Chiffrement du message impossible : " + e.getMessage(), e);
        }
    }

    /**
     * Ouvre une enveloppe avec les clés de cette box.
     *
     * @throws ProtocolException si l'enveloppe n'est pas destinée à cette box, est altérée ou mal formée
     */
    public static byte[] open(byte[] envelope, HybridKemKeyPair keys) {
        ByteBuffer in = ByteBuffer.wrap(envelope);
        if (envelope.length < MAGIC.length + 3 || !Arrays.equals(Arrays.copyOf(envelope, MAGIC.length), MAGIC)) {
            throw new ProtocolException("Enveloppe de message non reconnue");
        }
        in.position(MAGIC.length);
        int version = in.get() & 0xff;
        if (version != VERSION) {
            throw new ProtocolException("Version d'enveloppe non prise en charge : " + version);
        }
        int headerLength = in.getShort() & 0xffff;
        if (headerLength == 0 || headerLength > MAX_HEADER || in.remaining() < headerLength) {
            throw new ProtocolException("En-tête d'enveloppe invalide");
        }
        byte[] headerBytes = new byte[headerLength];
        in.get(headerBytes);
        Header header = Codec.fromJson(new String(headerBytes, StandardCharsets.UTF_8), Header.class,
                "En-tête d'enveloppe");
        if (!"IU1".equals(header.suite())) {
            throw new ProtocolException("Suite non prise en charge : " + header.suite());
        }
        if (!keys.publicKey().fingerprint().equals(header.recipient())) {
            throw new ProtocolException("Message chiffré pour une autre box");
        }
        byte[] prefix = prefix(headerBytes);
        byte[] sealed = new byte[in.remaining()];
        in.get(sealed);
        try {
            SecretKey key = derive(decapsulate("ML-KEM", keys.mlKem768(), header.mlKem768()),
                    decapsulate("DHKEM", keys.x25519(), header.x25519()), Codec.sha256(prefix));
            byte[] padded = gcm(Cipher.DECRYPT_MODE, key, Codec.sha256(prefix), sealed);
            int length = ByteBuffer.wrap(padded).getInt();
            if (length < 0 || length > padded.length - 4) {
                throw new ProtocolException("Longueur de message invalide");
            }
            return Arrays.copyOfRange(padded, 4, 4 + length);
        } catch (AEADBadTagException e) {
            throw new ProtocolException("Message altéré, ou clé de chiffrement différente", e);
        } catch (GeneralSecurityException e) {
            throw new ProtocolException("Déchiffrement du message impossible : " + e.getMessage(), e);
        }
    }

    static int tier(int length) {
        for (int tier : TIERS) {
            if (length <= tier) {
                return tier;
            }
        }
        throw new ProtocolException("Message trop long");
    }

    private static SecretKey decapsulate(String algorithm, KeyPair pair, String encapsulation)
            throws GeneralSecurityException {
        try {
            return KEM.getInstance(algorithm).newDecapsulator(pair.getPrivate())
                    .decapsulate(Base64.getDecoder().decode(encapsulation == null ? "" : encapsulation));
        } catch (DecapsulateException | IllegalArgumentException e) {
            throw new ProtocolException("Encapsulation " + algorithm + " invalide", e);
        }
    }

    private static SecretKey derive(SecretKey mlKem, SecretKey x25519, byte[] headerDigest)
            throws GeneralSecurityException {
        byte[] context = ByteBuffer.allocate(CONTEXT.length() + headerDigest.length)
                .put(CONTEXT.getBytes(StandardCharsets.US_ASCII)).put(headerDigest).array();
        return KDF.getInstance("HKDF-SHA256").deriveKey("AES",
                HKDFParameterSpec.ofExtract().addIKM(mlKem).addIKM(x25519).thenExpand(context, 32));
    }

    /** Clé à usage unique : nonce nul ; l'empreinte de l'en-tête est en données associées. */
    private static byte[] gcm(int mode, SecretKey key, byte[] headerDigest, byte[] data)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(128, new byte[12]));
        cipher.updateAAD(headerDigest);
        return cipher.doFinal(data);
    }

    private static byte[] prefix(byte[] header) {
        return ByteBuffer.allocate(MAGIC.length + 1 + 2 + header.length).put(MAGIC).put((byte) VERSION)
                .putShort((short) header.length).put(header).array();
    }
}
