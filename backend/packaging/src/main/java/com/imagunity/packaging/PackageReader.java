package com.imagunity.packaging;

import com.github.luben.zstd.ZstdInputStream;
import com.imagunity.domain.exam.DicomUids;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dépaquette un paquet reçu et vérifie son intégrité avant toute restitution.
 *
 * <p>Le paquet vient d'un partenaire : il est traité comme une donnée non fiable.
 * <ul>
 *   <li>seules les entrées {@code instances/<UID>/<UID>.dcm} et {@code manifest.json} sont admises,
 *       fichiers ordinaires uniquement (pas de lien, pas de dossier, pas de chemin relatif) ;</li>
 *   <li>le manifeste est la dernière entrée et sa taille est bornée ;</li>
 *   <li>le volume décompressé total est borné (protection contre les « bombes » de décompression) ;</li>
 *   <li>chaque instance doit figurer au manifeste avec la même taille et la même empreinte SHA-256,
 *       sans doublon, sans manque et sans surplus.</li>
 * </ul>
 * En cas d'échec, les fichiers déjà extraits sont supprimés.
 */
public final class PackageReader {

    static final int MAX_MANIFEST_SIZE = 16 * 1024 * 1024;
    private static final Pattern ENTRY = Pattern.compile(
            "instances/(" + DicomUids.REGEX + ")/(" + DicomUids.REGEX + ")\\.dcm");

    private record Extracted(String seriesUid, String sopUid, Path file, long size, String sha256) {
    }

    private final long maxUnpackedSize;
    private final JsonMapper mapper = JsonMapper.builder().build();

    /** @param maxUnpackedSize volume décompressé maximal accepté pour un paquet, en octets */
    public PackageReader(long maxUnpackedSize) {
        if (maxUnpackedSize <= 0) {
            throw new IllegalArgumentException("Le volume maximal doit être positif");
        }
        this.maxUnpackedSize = maxUnpackedSize;
    }

    /**
     * Extrait le paquet dans {@code targetDir} ({@code <Series UID>/<SOP UID>.dcm}) et vérifie son contenu.
     *
     * @throws PackageIntegrityException si le paquet est illisible, incomplet, altéré ou non conforme
     */
    public UnpackedPackage unpack(Path packageFile, Path targetDir) throws IOException {
        Files.createDirectories(targetDir);
        MessageDigest fileDigest = StudyPackager.sha256();
        Map<String, Extracted> extracted = new HashMap<>();
        PackageManifest manifest = null;
        try {
            try (InputStream file = Files.newInputStream(packageFile);
                 DigestInputStream digested = new DigestInputStream(new BufferedInputStream(file), fileDigest);
                 ZstdInputStream zstd = new ZstdInputStream(digested);
                 TarArchiveInputStream tar = new TarArchiveInputStream(zstd, "UTF-8")) {
                long total = 0;
                TarArchiveEntry entry;
                while ((entry = tar.getNextEntry()) != null) {
                    if (manifest != null) {
                        throw new PackageIntegrityException("Entrée après le manifeste");
                    }
                    // Type explicite : isFile() de Commons Compress accepte aussi les liens symboliques.
                    byte type = entry.getLinkFlag();
                    if (type != TarConstants.LF_NORMAL && type != TarConstants.LF_OLDNORM) {
                        throw new PackageIntegrityException("Entrée non admise (dossier, lien ou fichier spécial)");
                    }
                    String name = entry.getName();
                    if (name.equals(PackageManifest.ENTRY_NAME)) {
                        manifest = readManifest(tar, entry);
                        continue;
                    }
                    Matcher m = ENTRY.matcher(name);
                    if (!m.matches() || !DicomUids.isValid(m.group(1)) || !DicomUids.isValid(m.group(2))) {
                        throw new PackageIntegrityException("Nom d'entrée non admis");
                    }
                    if (extracted.containsKey(name)) {
                        throw new PackageIntegrityException("Instance en double dans le paquet");
                    }
                    total += entry.getSize();
                    if (total > maxUnpackedSize) {
                        throw new PackageIntegrityException("Volume décompressé supérieur à la limite");
                    }
                    Path out = targetDir.resolve(m.group(1)).resolve(m.group(2) + ".dcm");
                    Files.createDirectories(out.getParent());
                    MessageDigest digest = StudyPackager.sha256();
                    long size;
                    try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE_NEW)) {
                        // Enregistré avant l'écriture : supprimé en cas d'échec, même partiel.
                        extracted.put(name, new Extracted(m.group(1), m.group(2), out, -1, null));
                        size = new DigestInputStream(tar, digest).transferTo(os);
                    }
                    extracted.put(name, new Extracted(m.group(1), m.group(2), out, size, StudyPackager.hex(digest)));
                }
                // Lire la fin du fichier pour que l'empreinte couvre tous ses octets.
                digested.transferTo(OutputStream.nullOutputStream());
            }
            verify(manifest, extracted);
        } catch (IOException | RuntimeException e) {
            for (Extracted x : extracted.values()) {
                Files.deleteIfExists(x.file());
            }
            if (e instanceof PackageIntegrityException pie) {
                throw pie;
            }
            throw new PackageIntegrityException("Paquet illisible : " + e.getMessage(), e);
        }

        List<UnpackedPackage.Instance> instances = new ArrayList<>();
        for (PackageManifest.SeriesEntry series : manifest.series()) {
            for (PackageManifest.InstanceEntry instance : series.instances()) {
                instances.add(new UnpackedPackage.Instance(series.seriesInstanceUid(), instance,
                        extracted.get(instance.path()).file()));
            }
        }
        return new UnpackedPackage(manifest, instances, StudyPackager.hex(fileDigest));
    }

    private PackageManifest readManifest(InputStream tar, TarArchiveEntry entry) throws IOException {
        if (entry.getSize() > MAX_MANIFEST_SIZE) {
            throw new PackageIntegrityException("Manifeste trop volumineux");
        }
        ByteArrayOutputStream json = new ByteArrayOutputStream((int) entry.getSize());
        tar.transferTo(json);
        try {
            return mapper.readValue(json.toByteArray(), PackageManifest.class);
        } catch (JacksonException e) {
            throw new PackageIntegrityException("Manifeste illisible", e);
        }
    }

    private static void verify(PackageManifest manifest, Map<String, Extracted> extracted)
            throws PackageIntegrityException {
        if (manifest == null) {
            throw new PackageIntegrityException("Manifeste absent");
        }
        if (!PackageManifest.FORMAT.equals(manifest.format()) || manifest.version() != PackageManifest.VERSION) {
            throw new PackageIntegrityException("Format de paquet non pris en charge");
        }
        if (manifest.partIndex() < 1 || manifest.partIndex() > manifest.partCount()) {
            throw new PackageIntegrityException("Numérotation du paquet incohérente");
        }
        if (!DicomUids.isValid(manifest.studyInstanceUid())) {
            throw new PackageIntegrityException("Study Instance UID invalide dans le manifeste");
        }
        int expected = 0;
        for (PackageManifest.SeriesEntry series : manifest.series()) {
            for (PackageManifest.InstanceEntry instance : series.instances()) {
                expected++;
                String path = PackageManifest.entryPath(series.seriesInstanceUid(), instance.sopInstanceUid());
                if (!path.equals(instance.path())) {
                    throw new PackageIntegrityException("Chemin incohérent dans le manifeste");
                }
                Extracted x = extracted.get(path);
                if (x == null) {
                    throw new PackageIntegrityException("Instance du manifeste absente du paquet");
                }
                if (x.size() != instance.size() || !x.sha256().equalsIgnoreCase(instance.sha256())) {
                    throw new PackageIntegrityException("Instance altérée (taille ou empreinte différente)");
                }
            }
        }
        if (expected != extracted.size()) {
            throw new PackageIntegrityException("Instance du paquet absente du manifeste");
        }
    }
}
