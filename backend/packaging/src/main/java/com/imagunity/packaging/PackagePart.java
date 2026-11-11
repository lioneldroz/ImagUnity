package com.imagunity.packaging;

import java.nio.file.Path;

/**
 * Paquet produit pour une étude : un fichier {@code .tar.zst}, prêt à être confié au transport.
 *
 * @param file     fichier du paquet
 * @param manifest manifeste du paquet (aussi présent dans l'archive)
 * @param size     taille du fichier, en octets (après compression)
 * @param sha256   empreinte SHA-256 du fichier, en hexadécimal : contrôle de bout en bout du transport
 */
public record PackagePart(Path file, PackageManifest manifest, long size, String sha256) {

    public int index() {
        return manifest.partIndex();
    }

    public int count() {
        return manifest.partCount();
    }
}
