package com.imagunity.transport.iu;

import com.imagunity.protocol.ChunkLayout;
import com.imagunity.protocol.Codec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Taille de morceau adaptée à la liaison (spécification § 5.2) : la box mesure le débit de ses téléversements vers le
 * relais et choisit, pour chaque envoi, la plus grande taille admise qui se téléverse en {@link #TARGET} au plus.
 *
 * <ul>
 *   <li>liaison lente : petits morceaux, donc peu de travail perdu sur une coupure et, avec l'annonce anticipée, une
 *       restitution qui suit de près la fin du téléversement (le dernier morceau arrive vite) ;</li>
 *   <li>liaison rapide : gros morceaux, donc moins de requêtes ;</li>
 *   <li>au plus {@link #MAX_CHUNKS} morceaux par paquet : description courte et préparation rapide, même sur une
 *       liaison lente (un gros paquet garde alors des morceaux plus gros).</li>
 * </ul>
 * Le débit est une moyenne glissante (poids {@link #WEIGHT} à la dernière mesure), gardée dans le dossier d'état pour
 * survivre aux redémarrages. Sans mesure, la taille par défaut (4 Mio).
 */
final class IuLink {

    private static final System.Logger LOG = System.getLogger(IuLink.class.getName());
    /** Durée visée pour le téléversement d'un morceau. */
    static final Duration TARGET = Duration.ofSeconds(3);
    static final int MAX_CHUNKS = 512;
    static final double WEIGHT = 0.3;
    /** Mesures plus courtes ignorées : trop imprécises (morceau minuscule, horloge). */
    static final Duration MIN_SAMPLE = Duration.ofMillis(20);
    static final List<Integer> SIZES = List.of(256 * ChunkLayout.KIB, ChunkLayout.MIB, 4 * ChunkLayout.MIB,
            8 * ChunkLayout.MIB);

    /** Débit estimé, en octets par seconde, et date de la dernière mesure. */
    record Estimate(double bytesPerSecond, Instant at) {
    }

    private final Path file;
    private Estimate estimate;

    IuLink(Path stateDir) {
        this.file = stateDir.resolve("liaison.json");
        this.estimate = read(file);
    }

    private static Estimate read(Path file) {
        if (!Files.exists(file)) {
            return null;
        }
        try {
            Estimate e = Codec.json().readValue(Files.readString(file, StandardCharsets.UTF_8), Estimate.class);
            return e.bytesPerSecond() > 0 ? e : null;
        } catch (IOException | RuntimeException e) {
            LOG.log(Level.WARNING, "Débit de la liaison illisible, oublié : {0}", e.getMessage());
            return null;
        }
    }

    /** Enregistre un téléversement de {@code bytes} octets en {@code elapsed}. */
    synchronized void record(long bytes, Duration elapsed, Instant now) {
        if (bytes <= 0 || elapsed.compareTo(MIN_SAMPLE) < 0) {
            return;
        }
        double rate = bytes / (elapsed.toNanos() / 1e9);
        double next = estimate == null ? rate : WEIGHT * rate + (1 - WEIGHT) * estimate.bytesPerSecond();
        estimate = new Estimate(next, now);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, Codec.json().writeValueAsString(estimate), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Débit estimé en octets par seconde, s'il y a eu des mesures. */
    synchronized OptionalDouble bytesPerSecond() {
        return estimate == null ? OptionalDouble.empty() : OptionalDouble.of(estimate.bytesPerSecond());
    }

    /**
     * Taille de morceau pour un paquet.
     *
     * @param fixed taille imposée par la configuration, ou 0 pour l'adapter à la liaison
     */
    int chunkSize(long packetSize, int fixed) {
        if (fixed > 0) {
            return fixed;
        }
        OptionalDouble rate = bytesPerSecond();
        return choose(packetSize, rate.isPresent() ? rate.getAsDouble() : -1);
    }

    /**
     * Plus grande taille admise téléversée en {@link #TARGET} au plus, sans dépasser {@link #MAX_CHUNKS} morceaux.
     *
     * @param bytesPerSecond débit estimé, ou une valeur négative s'il est inconnu (taille par défaut)
     */
    static int choose(long packetSize, double bytesPerSecond) {
        int byRate;
        if (bytesPerSecond < 0) {
            byRate = ChunkLayout.DEFAULT_SIZE;
        } else {
            double budget = bytesPerSecond * TARGET.toNanos() / 1e9;
            byRate = SIZES.getFirst();
            for (int size : SIZES) {
                if (size <= budget) {
                    byRate = size;
                }
            }
        }
        int byCount = SIZES.getLast();
        for (int size : SIZES.reversed()) {
            if ((packetSize + size - 1) / size <= MAX_CHUNKS) {
                byCount = size;
            }
        }
        return Math.max(byRate, byCount);
    }
}
