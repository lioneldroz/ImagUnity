package com.imagunity.transport.iu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.protocol.ChunkLayout;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Taille de morceau adaptée au débit mesuré de la liaison. */
class IuLinkTest {

    private static final int KIB = ChunkLayout.KIB;
    private static final int MIB = ChunkLayout.MIB;
    private static final long MB_900 = 900L * 1000 * 1000;

    @TempDir
    Path dir;

    @Test
    void theChunkUploadsInAboutThreeSeconds() {
        assertThat(IuLink.choose(50 * MIB, -1)).as("débit inconnu").isEqualTo(4 * MIB);
        assertThat(IuLink.choose(50 * MIB, 50_000)).as("400 kbit/s").isEqualTo(256 * KIB);
        assertThat(IuLink.choose(50 * MIB, 250_000)).as("2 Mbit/s").isEqualTo(256 * KIB);
        assertThat(IuLink.choose(50 * MIB, 500_000)).as("4 Mbit/s").isEqualTo(MIB);
        assertThat(IuLink.choose(50 * MIB, 2_500_000)).as("20 Mbit/s").isEqualTo(4 * MIB);
        assertThat(IuLink.choose(50 * MIB, 50_000_000)).as("400 Mbit/s").isEqualTo(8 * MIB);
    }

    @Test
    void largePacketsKeepAShortDescription() {
        assertThat(IuLink.choose(MB_900, 50_000)).as("900 Mo sur liaison lente : 512 morceaux au plus")
                .isEqualTo(4 * MIB);
        assertThat(MB_900 / (4 * MIB)).isLessThanOrEqualTo(IuLink.MAX_CHUNKS);
        assertThat(IuLink.choose(3_000_000_000L, 50_000)).as("au-delà de 2 Gio : 8 Mio").isEqualTo(8 * MIB);
        assertThat(IuLink.choose(100 * MIB, 50_000)).isEqualTo(256 * KIB);
        assertThat(IuLink.choose(200 * MIB, 50_000)).as("200 Mio / 256 Kio = 800 morceaux").isEqualTo(MIB);
        assertThat(IuLink.choose(10, 50_000)).isEqualTo(256 * KIB);
    }

    @Test
    void theRateIsASlidingAverageKeptAcrossRestarts() throws Exception {
        IuLink link = new IuLink(dir);
        assertThat(link.bytesPerSecond()).isEmpty();
        assertThat(link.chunkSize(50 * MIB, 0)).isEqualTo(4 * MIB);
        assertThat(link.chunkSize(50 * MIB, MIB)).as("taille imposée").isEqualTo(MIB);

        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        link.record(1_000_000, Duration.ofSeconds(4), now);
        assertThat(link.bytesPerSecond().getAsDouble()).isEqualTo(250_000);
        link.record(4_000_000, Duration.ofSeconds(1), now);
        assertThat(link.bytesPerSecond().getAsDouble()).as("0,3 × 4 Mo/s + 0,7 × 250 ko/s")
                .isCloseTo(1_375_000, org.assertj.core.data.Offset.offset(1.0));
        link.record(1_000_000, Duration.ofMillis(5), now);
        link.record(0, Duration.ofSeconds(1), now);
        assertThat(link.bytesPerSecond().getAsDouble()).as("mesures trop courtes ou vides ignorées")
                .isCloseTo(1_375_000, org.assertj.core.data.Offset.offset(1.0));

        IuLink again = new IuLink(dir);
        assertThat(again.bytesPerSecond().getAsDouble()).isCloseTo(1_375_000, org.assertj.core.data.Offset.offset(1.0));
        assertThat(again.chunkSize(50 * MIB, 0)).isEqualTo(MIB);

        Files.writeString(dir.resolve("liaison.json"), "illisible");
        assertThat(new IuLink(dir).bytesPerSecond()).isEmpty();
        Files.writeString(dir.resolve("liaison.json"), "{\"bytesPerSecond\":0,\"at\":\"2026-11-12T10:00:00Z\"}");
        assertThat(new IuLink(dir).bytesPerSecond()).isEmpty();
    }

    @Test
    void anUnwritableStateFailsLoudlyAndBadSizesAreRefused() throws Exception {
        IuLink link = new IuLink(dir.resolve("absent"));
        assertThatThrownBy(() -> link.record(1_000_000, Duration.ofSeconds(1), Instant.now()))
                .isInstanceOf(java.io.UncheckedIOException.class);
        assertThatThrownBy(() -> new IuTransport.Settings(URI.create("http://r"), dir, Duration.ofHours(1), 1,
                Duration.ofDays(1), true, 3 * MIB)).hasMessageContaining("non admise");
        assertThat(new IuTransport.Settings(URI.create("http://r"), dir, Duration.ofHours(1), 1, Duration.ofDays(1),
                true, MIB).chunkSize()).isEqualTo(MIB);
    }
}
