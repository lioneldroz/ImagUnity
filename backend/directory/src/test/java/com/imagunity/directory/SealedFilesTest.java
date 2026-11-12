package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SealedFilesTest {

    private static final HybridKemKeyPair BOX_B = HybridKemKeyPair.generate();
    private static final String NAME = "paquet-1-of-1.tar.zst.iu1";
    private static final int CHUNK = SealedFiles.MIN_CHUNK;

    @TempDir
    Path dir;

    private Path plain(int size) throws IOException {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return Files.write(dir.resolve("clair-" + size), data);
    }

    private Path seal(Path plain) throws IOException {
        Path sealed = dir.resolve(plain.getFileName() + SealedFiles.EXTENSION);
        SealedFiles.seal(plain, sealed, BOX_B.publicKey(), NAME, CHUNK);
        return sealed;
    }

    private Path open(Path sealed, HybridKemKeyPair keys, String name) throws IOException {
        Path out = dir.resolve("ouvert-" + System.nanoTime());
        SealedFiles.open(sealed, out, keys, name);
        return out;
    }

    @Test
    void onlyTheRecipientRecoversTheExactContent() throws IOException {
        for (int size : new int[] {0, 1, CHUNK - 1, CHUNK, 3 * CHUNK, 3 * CHUNK + 17}) {
            Path plain = plain(size);
            Path sealed = seal(plain);

            assertThat(SealedFiles.isSealed(sealed)).isTrue();
            assertThat(Files.readAllBytes(open(sealed, BOX_B, NAME))).as("taille %d", size)
                    .isEqualTo(Files.readAllBytes(plain));
            if (size > 64) {
                byte[] content = Files.readAllBytes(sealed);
                byte[] sample = Arrays.copyOfRange(Files.readAllBytes(plain), 0, 32);
                assertThat(indexOf(content, sample)).as("le clair n'apparaît pas dans le chiffré").isEqualTo(-1);
            }
        }
        assertThat(SealedFiles.isSealed(plain(10))).isFalse();
    }

    @Test
    void defaultChunkSizeHandlesLargerFiles() throws IOException {
        Path plain = plain(SealedFiles.CHUNK_SIZE + 5);
        Path sealed = dir.resolve("grand.iu1");
        SealedFiles.seal(plain, sealed, BOX_B.publicKey(), NAME);

        assertThat(Files.readAllBytes(open(sealed, BOX_B, NAME))).isEqualTo(Files.readAllBytes(plain));
        assertThatThrownBy(() -> SealedFiles.seal(plain, sealed, BOX_B.publicKey(), NAME))
                .as("jamais écrasé").isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        Path existing = Files.writeString(dir.resolve("existant"), "à garder");
        assertThatThrownBy(() -> SealedFiles.open(sealed, existing, BOX_B, NAME))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.size(sealed)).as("chiffré gardé").isGreaterThan(SealedFiles.CHUNK_SIZE);
        assertThat(Files.readString(existing)).isEqualTo("à garder");
    }

    @Test
    void anotherBoxOrAnotherNameIsRefused() throws IOException {
        Path sealed = seal(plain(100));

        assertThatThrownBy(() -> open(sealed, HybridKemKeyPair.generate(), NAME))
                .isInstanceOf(DirectoryException.class).hasMessageContaining("autre box");
        assertThatThrownBy(() -> open(sealed, BOX_B, "autre-1-of-1.tar.zst.iu1"))
                .hasMessageContaining("autre nom");
        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(n -> n.startsWith("ouvert-"));
        }
    }

    @Test
    void anyAlterationIsDetected() throws IOException {
        Path sealed = seal(plain(3 * CHUNK + 5));
        byte[] genuine = Files.readAllBytes(sealed);
        int payload = payloadOffset(genuine);

        // Un octet modifié dans un bloc, puis dans l'en-tête (encapsulation).
        assertAltered(flip(genuine, payload + 10), "altéré");
        assertAltered(flip(genuine, genuine.length - 1), "altéré");
        // Tronqué au ras d'un bloc : le nouveau dernier bloc n'était pas marqué « dernier ».
        assertAltered(Arrays.copyOf(genuine, payload + CHUNK + 16), "altéré");
        assertAltered(Arrays.copyOf(genuine, genuine.length + 40), "altéré");
        assertAltered(Arrays.copyOf(genuine, payload + 3), "tronqué");
        // Blocs échangés.
        byte[] swapped = genuine.clone();
        System.arraycopy(genuine, payload, swapped, payload + CHUNK + 16, CHUNK + 16);
        System.arraycopy(genuine, payload + CHUNK + 16, swapped, payload, CHUNK + 16);
        assertAltered(swapped, "altéré");
    }

    @Test
    void malformedFilesAreRefused() throws IOException {
        byte[] genuine = Files.readAllBytes(seal(plain(50)));
        int headerLength = ByteBuffer.wrap(genuine, 5, 4).getInt();
        String header = new String(genuine, 9, headerLength, StandardCharsets.UTF_8);

        assertAltered("PAS UN PAQUET".getBytes(StandardCharsets.US_ASCII), "non chiffré");
        byte[] version = genuine.clone();
        version[4] = 9;
        assertAltered(version, "Version");
        assertAltered(withLength(genuine, 0), "En-tête");
        assertAltered(withLength(genuine, SealedFiles.MAX_HEADER + 1), "En-tête");
        assertAltered(Arrays.copyOf(genuine, 9 + headerLength / 2), "tronqué");
        assertAltered(Arrays.copyOf(genuine, 7), "tronqué");
        assertAltered(rebuild(genuine, header.replace("\"IU1\"", "\"IU2\"")), "IU2");
        assertAltered(rebuild(genuine, header.replace("\"chunkSize\":" + CHUNK, "\"chunkSize\":12")), "bloc");
        assertAltered(rebuild(genuine, header.replace("\"chunkSize\":" + CHUNK, "\"chunkSize\":999999999")),
                "bloc");
        assertAltered(rebuild(genuine, header.replaceFirst("\"x25519\":\"[^\"]+\"", "\"x25519\":\"%%%\"")),
                "illisible");
        assertAltered(rebuild(genuine, header.replaceFirst("\"x25519\":\"[^\"]+\"", "\"x25519\":\"AAAA\"")),
                "invalide");
        assertAltered(rebuild(genuine, header.replaceFirst("\"mlKem768\":\"[^\"]+\"", "\"mlKem768\":\"AAAA\"")),
                "invalide");
        assertAltered(rebuild(genuine, header.replaceFirst(",\"x25519\":\"[^\"]+\"", "")), "invalide");
        assertAltered(rebuild(genuine, "{\"inconnu\":1}"), "illisible");
    }

    private void assertAltered(byte[] content, String message) throws IOException {
        Path file = Files.write(dir.resolve("altere-" + System.nanoTime()), content);
        assertThatThrownBy(() -> open(file, BOX_B, NAME)).isInstanceOf(DirectoryException.class)
                .hasMessageContaining(message);
    }

    private static int payloadOffset(byte[] sealed) {
        return 9 + ByteBuffer.wrap(sealed, 5, 4).getInt();
    }

    private static byte[] flip(byte[] content, int index) {
        byte[] copy = content.clone();
        copy[index] ^= 1;
        return copy;
    }

    private static byte[] withLength(byte[] content, int length) {
        byte[] copy = content.clone();
        ByteBuffer.wrap(copy, 5, 4).putInt(length);
        return copy;
    }

    private static byte[] rebuild(byte[] genuine, String header) {
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        int offset = payloadOffset(genuine);
        return ByteBuffer.allocate(9 + h.length + genuine.length - offset).put(genuine, 0, 5).putInt(h.length).put(h)
                .put(genuine, offset, genuine.length - offset).array();
    }

    private static int indexOf(byte[] data, byte[] sample) {
        outer:
        for (int i = 0; i <= data.length - sample.length; i++) {
            for (int j = 0; j < sample.length; j++) {
                if (data[i + j] != sample[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
