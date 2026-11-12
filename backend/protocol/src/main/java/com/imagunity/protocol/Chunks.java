package com.imagunity.protocol;

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
 */
public final class Chunks {

    private Chunks() {
    }

    /** Morceau {@code index} du paquet, complété par des zéros jusqu'à la taille de morceau. */
    public static byte[] read(Path packet, ChunkLayout layout, int index) throws IOException {
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
        return chunk;
    }

    /** Empreintes de tous les morceaux du paquet, dans l'ordre. */
    public static List<String> digests(Path packet, ChunkLayout layout) throws IOException {
        List<String> digests = new ArrayList<>(layout.chunkCount());
        for (int i = 0; i < layout.chunkCount(); i++) {
            digests.add(Codec.sha256Hex(read(packet, layout, i)));
        }
        return digests;
    }

    /**
     * Écrit un morceau téléchargé à sa place dans le paquet en cours de réassemblage, après vérification de son
     * empreinte et de son remplissage.
     *
     * @throws ProtocolException si le morceau n'a pas la taille, l'empreinte ou le remplissage attendus
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
        for (int i = payload; i < chunk.length; i++) {
            if (chunk[i] != 0) {
                throw new ProtocolException("Morceau " + index + " : remplissage non nul");
            }
        }
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(chunk, 0, payload);
            long position = layout.offset(index);
            while (buffer.hasRemaining()) {
                position += channel.write(buffer, position);
            }
        }
    }
}
