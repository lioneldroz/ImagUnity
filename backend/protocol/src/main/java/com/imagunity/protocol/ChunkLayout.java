package com.imagunity.protocol;

import java.util.Set;

/**
 * Découpage d'un paquet en morceaux de taille fixe (spécification § 5.2) : le dernier est complété par des zéros, le
 * relais ne voit que des morceaux de tailles standard.
 *
 * @param size       taille réelle du paquet, en octets
 * @param chunkSize  taille des morceaux, parmi {@link #SIZES}
 * @param chunkCount nombre de morceaux
 */
public record ChunkLayout(long size, int chunkSize, int chunkCount) {

    public static final int KIB = 1024;
    public static final int MIB = 1024 * KIB;
    /** Tailles de morceau admises. */
    public static final Set<Integer> SIZES = Set.of(256 * KIB, MIB, 4 * MIB, 8 * MIB);
    /** Taille par défaut. */
    public static final int DEFAULT_SIZE = 4 * MIB;
    /** Nombre maximal de morceaux d'un paquet. */
    public static final int MAX_CHUNKS = 100_000;

    public ChunkLayout {
        if (!SIZES.contains(chunkSize)) {
            throw new ProtocolException("Taille de morceau non admise : " + chunkSize);
        }
        if (size < 1) {
            throw new ProtocolException("Paquet vide");
        }
        long expected = (size + chunkSize - 1) / chunkSize;
        if (expected > MAX_CHUNKS || chunkCount != expected) {
            throw new ProtocolException("Nombre de morceaux incohérent : " + chunkCount + " pour " + size + " octets");
        }
    }

    /** Découpage d'un paquet de cette taille. */
    public static ChunkLayout of(long size, int chunkSize) {
        if (!SIZES.contains(chunkSize)) {
            throw new ProtocolException("Taille de morceau non admise : " + chunkSize);
        }
        long count = (size + chunkSize - 1) / chunkSize;
        if (count > MAX_CHUNKS) {
            throw new ProtocolException("Paquet trop gros pour cette taille de morceau");
        }
        return new ChunkLayout(size, chunkSize, (int) count);
    }

    /** Position du morceau dans le paquet. */
    public long offset(int index) {
        check(index);
        return (long) index * chunkSize;
    }

    /** Nombre d'octets utiles du morceau (le reste est du remplissage). */
    public int payload(int index) {
        check(index);
        return (int) Math.min(chunkSize, size - offset(index));
    }

    private void check(int index) {
        if (index < 0 || index >= chunkCount) {
            throw new ProtocolException("Morceau hors du paquet : " + index);
        }
    }
}
