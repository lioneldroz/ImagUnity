package com.imagunity.packaging;

import java.nio.file.Path;
import java.util.List;

/**
 * Paquet dépaqueté et vérifié : toutes les instances du manifeste sont présentes, avec la taille
 * et l'empreinte attendues.
 *
 * @param manifest  manifeste du paquet
 * @param instances instances extraites, dans l'ordre du manifeste
 * @param sha256    empreinte SHA-256 du fichier paquet, à comparer à celle annoncée par l'émetteur
 */
public record UnpackedPackage(PackageManifest manifest, List<Instance> instances, String sha256) {

    public UnpackedPackage {
        instances = List.copyOf(instances);
    }

    /**
     * Une instance extraite.
     *
     * @param seriesInstanceUid série de l'instance
     * @param entry             description de l'instance dans le manifeste
     * @param file              fichier DICOM extrait
     */
    public record Instance(String seriesInstanceUid, PackageManifest.InstanceEntry entry, Path file) {
    }
}
