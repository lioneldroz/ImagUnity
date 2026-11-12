package com.imagunity.protocol;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Lecture des morceaux d'un paquet, et réassemblage vérifié (spécification § 5.3) : chaque morceau complété a son
 * empreinte SHA-256 ; le paquet réassemblé, la sienne.
 *
 * <p>Le dernier morceau est complété jusqu'à la taille de morceau : par des octets pseudo-aléatoires tirés d'une clé
 * de remplissage propre à l'envoi (AES-256-CTR, nonce nul), pour que le relais ne lise pas la taille exacte du paquet
 * dans le remplissage ; ou par des zéros sans clé (vecteurs de test, envois d'avant la v0.5). Le destinataire ne
 * vérifie que l'empreinte du morceau, qui couvre le remplissage.
 */
public final class Chunks {

    private Chunks() {
    }

    /** Taille d'une clé de remplissage. */
    public static final int PADDING_KEY = 32;

    /** Morceau {@code index} du paquet, complété par des zéros jusqu'à la taille de morceau. */
    public static byte[] read(Path packet, ChunkLayout layout, int index) throws IOException {
        return read(packet, layout, index, null);
    }

    /**
     * Morceau {@code index} du paquet, complété jusqu'à la taille de morceau.
     *
     * @param paddingKey clé de remplissage de l'envoi ({@value #PADDING_KEY} octets), ou {@code null} pour des zéros
     */
    public static byte[] read(Path packet, ChunkLayout layout, int index, byte[] paddingKey) throws IOException {
        byte[] chunk = new byte[layout.chunkSize()];
        try (FileChannel channel = FileChannel.open(packet, StandardOpenOption.READ)) {
            if (channel.size() != layout.size()) {
                throw new ProtocolException("Paquet modifié : taille différente du découpage");
            }
            ByteBuffer buffer = ByteBuffer.wrap(chunk, 0, layout.payload(index));
            long position = layout.offset(index);
            while (buffer.hasRemaining()) {
                int n = channel.read(buffer, position);
                if (n < 0) {
                    throw new ProtocolException("Paquet tronqué");
                }
                position += n;
            }
        }
        int payload = layout.payload(index);
        if (paddingKey != null && payload < chunk.length) {
            byte[] padding = padding(paddingKey, chunk.length - payload);
            System.arraycopy(padding, 0, chunk, payload, padding.length);
        }
        return chunk;
    }

    /** Remplissage : les {@code length} premiers octets du flux AES-256-CTR de la clé (nonce nul). */
    static byte[] padding(byte[] key, int length) {
        if (key.length != PADDING_KEY) {
            throw new ProtocolException("Clé de remplissage invalide");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CTR/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
            return cipher.doFinal(new byte[length]);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Empreintes de tous les morceaux du paquet complétés par des zéros, dans l'ordre. */
    public static List<String> digests(Path packet, ChunkLayout layout) throws IOException {
        return digests(packet, layout, null);
    }

    /** Empreintes de tous les morceaux du paquet, dans l'ordre. */
    public static List<String> digests(Path packet, ChunkLayout layout, byte[] paddingKey) throws IOException {
        List<String> digests = new ArrayList<>(layout.chunkCount());
        for (int i = 0; i < layout.chunkCount(); i++) {
            digests.add(Codec.sha256Hex(read(packet, layout, i, paddingKey)));
        }
        return digests;
    }

    /**
     * Écrit un morceau téléchargé à sa place dans le paquet en cours de réassemblage, après vérification de son
     * empreinte (qui couvre le remplissage) ; le remplissage n'est pas écrit.
     *
     * @throws ProtocolException si le morceau n'a pas la taille ou l'empreinte attendues
     */
    public static void write(Path target, ChunkLayout layout, int index, byte[] chunk, String expectedDigest)
            throws IOException {
        if (chunk.length != layout.chunkSize()) {
            throw new ProtocolException("Morceau " + index + " de taille inattendue");
        }
        if (!MessageDigest.isEqual(Codec.utf8(Codec.sha256Hex(chunk)), Codec.utf8(expectedDigest))) {
            throw new ProtocolException("Morceau " + index + " altéré : empreinte différente de la description");
        }
        int payload = layout.payload(index);
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(chunk, 0, payload);
            long position = layout.offset(index);
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
        }
    }
}
