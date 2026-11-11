package com.imagunity.packaging;

import java.util.List;
import java.util.stream.Stream;

/**
 * Manifeste d'un paquet : ce qu'il contient, et de quoi vérifier que tout est arrivé intact.
 *
 * <p>Il est écrit en JSON, en <b>dernière</b> entrée de l'archive : les empreintes sont ainsi calculées
 * pendant l'écriture, en une seule lecture des instances.
 *
 * @param format           toujours {@value #FORMAT}
 * @param version          version du format, {@value #VERSION}
 * @param packageGroupId   identifiant commun à tous les paquets d'une même étude
 * @param partIndex        rang de ce paquet, à partir de 1
 * @param partCount        nombre de paquets de l'étude
 * @param studyInstanceUid étude, identifiant conservé tel quel
 * @param createdAt        date de création (ISO 8601, UTC)
 * @param studyTotals      totaux de l'étude entière, tous paquets confondus
 * @param series           séries (ou parties de séries) contenues dans ce paquet
 */
public record PackageManifest(
        String format,
        int version,
        String packageGroupId,
        int partIndex,
        int partCount,
        String studyInstanceUid,
        String createdAt,
        Totals studyTotals,
        List<SeriesEntry> series) {

    public static final String FORMAT = "imagunity-package";
    public static final int VERSION = 1;

    /** Nom de l'entrée du manifeste dans l'archive. */
    public static final String ENTRY_NAME = "manifest.json";

    public PackageManifest {
        series = series == null ? List.of() : List.copyOf(series);
    }

    /** Nombre d'instances et taille totale. */
    public record Totals(int instances, long bytes) {
    }

    /** Une série, ou la partie d'une série contenue dans ce paquet. */
    public record SeriesEntry(String seriesInstanceUid, List<InstanceEntry> instances) {

        public SeriesEntry {
            instances = instances == null ? List.of() : List.copyOf(instances);
        }
    }

    /**
     * Une instance DICOM du paquet.
     *
     * @param path   chemin de l'entrée dans l'archive ({@code instances/<Series UID>/<SOP UID>.dcm})
     * @param size   taille du fichier DICOM, en octets
     * @param sha256 empreinte SHA-256 du fichier DICOM, en hexadécimal
     */
    public record InstanceEntry(
            String sopInstanceUid,
            String sopClassUid,
            String transferSyntaxUid,
            String path,
            long size,
            String sha256) {
    }

    /** Toutes les instances de ce paquet. */
    public Stream<InstanceEntry> instances() {
        return series.stream().flatMap(s -> s.instances().stream());
    }

    /** Totaux de ce paquet seul. */
    public Totals partTotals() {
        return new Totals((int) instances().count(), instances().mapToLong(InstanceEntry::size).sum());
    }

    /** Chemin d'une instance dans l'archive. */
    static String entryPath(String seriesInstanceUid, String sopInstanceUid) {
        return "instances/" + seriesInstanceUid + "/" + sopInstanceUid + ".dcm";
    }
}
