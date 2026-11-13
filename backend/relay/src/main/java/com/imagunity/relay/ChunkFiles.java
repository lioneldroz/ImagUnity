package com.imagunity.relay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Contenu des morceaux sur disque : {@code <dossier>/<2 premiers caractères>/<senderId>}. Un téléversement est écrit
 * dans un fichier temporaire, vérifié, puis mis en place d'un coup. Les fichiers temporaires restent sur ce disque
 * même quand le contenu va sur un stockage S3 ({@link S3ChunkContents}).
 */
public final class ChunkFiles implements ChunkContents {

    private final Path root;

    public ChunkFiles(Path root) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        Files.createDirectories(this.root.resolve(".tmp"));
    }

    public Path root() {
        return root;
    }

    public Path path(String senderId) {
        return root.resolve(senderId.substring(0, 2)).resolve(senderId);
    }

    /**
     * Téléversement reçu.
     *
     * @param temp   fichier temporaire
     * @param size   octets reçus
     * @param sha256 empreinte du contenu reçu, en hexadécimal
     */
    public record Received(Path temp, long size, String sha256) {
    }

    /** Écrit au plus {@code limit} + 1 octets du flux dans un fichier temporaire, en calculant l'empreinte. */
    public Received receive(InputStream in, long limit) throws IOException {
        Path temp = root.resolve(".tmp").resolve(UUID.randomUUID() + ".part");
        MessageDigest digest = sha256();
        long size;
        try (OutputStream out = new DigestOutputStream(Files.newOutputStream(temp), digest)) {
            size = in.transferTo(new LimitedOutputStream(out, limit + 1));
        } catch (TooLarge e) {
            Files.deleteIfExists(temp);
            throw new RelayException(org.springframework.http.HttpStatus.CONTENT_TOO_LARGE, e.getMessage());
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        return new Received(temp, size, HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public void commit(Received received, String senderId) throws IOException {
        Path target = path(senderId);
        Files.createDirectories(target.getParent());
        Files.move(received.temp(), target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    @Override
    public void delete(String senderId) throws IOException {
        Files.deleteIfExists(path(senderId));
    }

    @Override
    public org.springframework.core.io.Resource open(String senderId, long size) {
        return new org.springframework.core.io.FileSystemResource(path(senderId));
    }

    @Override
    public java.util.OptionalLong usableSpace() throws IOException {
        return java.util.OptionalLong.of(Files.getFileStore(root).getUsableSpace());
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Corps plus gros que le morceau annoncé. */
    static final class TooLarge extends IOException {

        TooLarge() {
            super("Morceau plus gros qu'annoncé");
        }
    }

    /** Coupe l'écriture au-delà de la limite (le relais refuse ensuite le morceau). */
    private static final class LimitedOutputStream extends OutputStream {

        private final OutputStream out;
        private long remaining;

        LimitedOutputStream(OutputStream out, long limit) {
            this.out = out;
            this.remaining = limit;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len > remaining) {
                throw new TooLarge();
            }
            out.write(b, off, len);
            remaining -= len;
        }
    }
}
