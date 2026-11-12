package com.imagunity.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunksTest {

    @TempDir
    Path dir;

    @Test
    void packetIsSplitPaddedAndReassembledExactlyInAnyOrder() throws IOException {
        byte[] packet = new byte[3 * ChunkLayout.MIB + 12345];
        new Random(7).nextBytes(packet);
        Path source = Files.write(dir.resolve("paquet.iu1"), packet);
        ChunkLayout layout = ChunkLayout.of(packet.length, ChunkLayout.MIB);
        List<String> digests = Chunks.digests(source, layout);

        assertThat(layout.chunkCount()).isEqualTo(4);
        assertThat(layout.payload(3)).isEqualTo(12345);
        byte[] last = Chunks.read(source, layout, 3);
        assertThat(last).hasSize(ChunkLayout.MIB);
        assertThat(java.util.Arrays.copyOfRange(last, 12345, last.length)).containsOnly((byte) 0);

        Path target = dir.resolve("reassemble.iu1");
        for (int index : new int[] {2, 0, 3, 1}) {
            Chunks.write(target, layout, index, Chunks.read(source, layout, index), digests.get(index));
        }
        assertThat(Files.readAllBytes(target)).isEqualTo(packet);
    }

    @Test
    void alteredChunksAreRefused() throws IOException {
        byte[] packet = new byte[300_000];
        new Random(3).nextBytes(packet);
        Path source = Files.write(dir.resolve("paquet.iu1"), packet);
        ChunkLayout layout = ChunkLayout.of(packet.length, 256 * ChunkLayout.KIB);
        List<String> digests = Chunks.digests(source, layout);
        Path target = dir.resolve("cible.iu1");
        byte[] chunk = Chunks.read(source, layout, 1);

        byte[] flipped = chunk.clone();
        flipped[0] ^= 1;
        assertThatThrownBy(() -> Chunks.write(target, layout, 1, flipped, digests.get(1))).hasMessageContaining("altéré");
        assertThatThrownBy(() -> Chunks.write(target, layout, 1, chunk, digests.get(0))).hasMessageContaining("altéré");
        assertThatThrownBy(() -> Chunks.write(target, layout, 1, new byte[10], digests.get(1)))
                .hasMessageContaining("taille");
        byte[] dirtyPadding = chunk.clone();
        dirtyPadding[dirtyPadding.length - 1] = 9;
        assertThatThrownBy(() -> Chunks.write(target, layout, 1, dirtyPadding, Codec.sha256Hex(dirtyPadding)))
                .hasMessageContaining("remplissage");

        Files.write(source, new byte[10]);
        assertThatThrownBy(() -> Chunks.read(source, layout, 0)).hasMessageContaining("modifié");
    }

    @Test
    void layoutsAreValidated() {
        assertThat(ChunkLayout.of(1, ChunkLayout.DEFAULT_SIZE).chunkCount()).isEqualTo(1);
        assertThat(ChunkLayout.of(8L * ChunkLayout.MIB, 8 * ChunkLayout.MIB).payload(0)).isEqualTo(8 * ChunkLayout.MIB);
        assertThatThrownBy(() -> ChunkLayout.of(10, 1000)).hasMessageContaining("non admise");
        assertThatThrownBy(() -> new ChunkLayout(10, 1000, 1)).hasMessageContaining("non admise");
        assertThatThrownBy(() -> ChunkLayout.of(0, ChunkLayout.MIB)).hasMessageContaining("vide");
        assertThatThrownBy(() -> new ChunkLayout(10, ChunkLayout.MIB, 2)).hasMessageContaining("incohérent");
        assertThatThrownBy(() -> ChunkLayout.of(256L * ChunkLayout.KIB * (ChunkLayout.MAX_CHUNKS + 1),
                256 * ChunkLayout.KIB)).hasMessageContaining("trop gros");
        ChunkLayout layout = ChunkLayout.of(10, ChunkLayout.MIB);
        assertThatThrownBy(() -> layout.offset(1)).hasMessageContaining("hors du paquet");
        assertThatThrownBy(() -> layout.payload(-1)).hasMessageContaining("hors du paquet");
    }
}
