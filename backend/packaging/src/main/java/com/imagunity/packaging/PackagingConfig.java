package com.imagunity.packaging;

/**
 * Réglages de l'empaquetage.
 *
 * @param maxPartSize      taille maximale (avant compression) du contenu d'un paquet, en octets. Au-delà,
 *                         l'étude est découpée en plusieurs paquets, de préférence aux frontières de séries.
 *                         Par défaut 900 Mo, sous la limite de 1 Go de XFTP. Une instance plus grande que
 *                         cette limite forme à elle seule un paquet.
 * @param compressionLevel niveau de compression zstd (1 à 22 ; 3 par défaut, bon compromis vitesse / taux)
 */
public record PackagingConfig(long maxPartSize, int compressionLevel) {

    /** 900 Mo : marge sous la limite « souple » de 1 Go des fichiers XFTP. */
    public static final long DEFAULT_MAX_PART_SIZE = 900L * 1024 * 1024;
    public static final int DEFAULT_COMPRESSION_LEVEL = 3;

    public PackagingConfig {
        if (maxPartSize <= 0) {
            throw new IllegalArgumentException("La taille maximale d'un paquet doit être positive");
        }
        if (compressionLevel < 1 || compressionLevel > 22) {
            throw new IllegalArgumentException("Niveau de compression zstd invalide : " + compressionLevel);
        }
    }

    public static PackagingConfig defaults() {
        return new PackagingConfig(DEFAULT_MAX_PART_SIZE, DEFAULT_COMPRESSION_LEVEL);
    }
}
