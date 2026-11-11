package com.imagunity.packaging;

import com.github.luben.zstd.ZstdOutputStream;
import com.imagunity.domain.exam.DicomUids;
import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * Empaquette une étude reçue en un ou plusieurs paquets {@code .tar.zst}.
 *
 * <p>Contenu d'un paquet :
 * <pre>
 * instances/&lt;Series UID&gt;/&lt;SOP UID&gt;.dcm   fichiers DICOM, octet pour octet
 * manifest.json                             manifeste (dernière entrée)
 * </pre>
 * L'archive est un tar POSIX standard compressé sans perte par zstd : elle reste lisible avec
 * les outils courants ({@code zstd -d paquet.tar.zst && tar xf paquet.tar}).
 *
 * <p>Les identifiants DICOM ne sont pas modifiés et chaque instance porte son empreinte SHA-256 :
 * le destinataire peut attester que l'examen est arrivé complet et identique.
 */
public final class StudyPackager {

    private static final System.Logger LOG = System.getLogger(StudyPackager.class.getName());
    private static final String SUFFIX = ".tar.zst";

    private final PackagingConfig config;
    private final PartPlanner planner;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public StudyPackager(PackagingConfig config) {
        this(config, Clock.systemUTC());
    }

    StudyPackager(PackagingConfig config, Clock clock) {
        this.config = config;
        this.planner = new PartPlanner(config.maxPartSize());
        this.clock = clock;
    }

    /**
     * Produit les paquets de l'étude dans {@code outputDir}. En cas d'échec, aucun paquet partiel ne reste.
     *
     * @return les paquets, dans l'ordre (rang 1 à n)
     */
    public List<PackagePart> pack(ReceivedStudy study, Path outputDir) throws IOException {
        if (study.instances().isEmpty()) {
            throw new IllegalArgumentException("Étude sans instance");
        }
        requireUid(study.studyInstanceUid(), "Study Instance UID");
        for (ReceivedInstance instance : study.instances()) {
            requireUid(instance.seriesInstanceUid(), "Series Instance UID");
            requireUid(instance.sopInstanceUid(), "SOP Instance UID");
        }
        Files.createDirectories(outputDir);

        List<List<ReceivedInstance>> plan = planner.plan(study.instances());
        String groupId = UUID.randomUUID().toString();
        Instant createdAt = clock.instant();
        long studyBytes = 0;
        for (ReceivedInstance instance : study.instances()) {
            studyBytes += Files.size(instance.file());
        }
        PackageManifest.Totals studyTotals = new PackageManifest.Totals(study.instances().size(), studyBytes);

        List<PackagePart> parts = new ArrayList<>();
        try {
            for (int i = 0; i < plan.size(); i++) {
                parts.add(writePart(study, plan.get(i), i + 1, plan.size(), groupId, createdAt, studyTotals, outputDir));
            }
        } catch (IOException | RuntimeException e) {
            for (PackagePart part : parts) {
                Files.deleteIfExists(part.file());
            }
            throw e;
        }
        LOG.log(Level.INFO, "Étude empaquetée en {0} paquet(s) : {1} instance(s), {2} octets → {3} octets compressés",
                parts.size(), studyTotals.instances(), studyTotals.bytes(),
                parts.stream().mapToLong(PackagePart::size).sum());
        return parts;
    }

    private PackagePart writePart(ReceivedStudy study, List<ReceivedInstance> instances, int index, int count,
                                  String groupId, Instant createdAt, PackageManifest.Totals studyTotals,
                                  Path outputDir) throws IOException {
        Path target = outputDir.resolve(groupId + "-" + index + "-of-" + count + SUFFIX);
        Path temp = outputDir.resolve(target.getFileName() + ".part");
        MessageDigest fileDigest = sha256();
        Map<String, List<PackageManifest.InstanceEntry>> series = new LinkedHashMap<>();
        PackageManifest manifest;
        try {
            try (OutputStream file = Files.newOutputStream(temp);
                 DigestOutputStream digested = new DigestOutputStream(new BufferedOutputStream(file), fileDigest);
                 ZstdOutputStream zstd = new ZstdOutputStream(digested, config.compressionLevel());
                 TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd, "UTF-8")) {
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX);
                FileTime modTime = FileTime.from(createdAt);

                for (ReceivedInstance instance : instances) {
                    String path = PackageManifest.entryPath(instance.seriesInstanceUid(), instance.sopInstanceUid());
                    long size = Files.size(instance.file());
                    TarArchiveEntry entry = new TarArchiveEntry(path);
                    entry.setSize(size);
                    entry.setLastModifiedTime(modTime);
                    tar.putArchiveEntry(entry);
                    MessageDigest digest = sha256();
                    try (InputStream in = new DigestInputStream(Files.newInputStream(instance.file()), digest)) {
                        if (in.transferTo(tar) != size) {
                            throw new IOException("Instance modifiée pendant l'empaquetage");
                        }
                    }
                    tar.closeArchiveEntry();
                    series.computeIfAbsent(instance.seriesInstanceUid(), k -> new ArrayList<>())
                            .add(new PackageManifest.InstanceEntry(instance.sopInstanceUid(), instance.sopClassUid(),
                                    instance.transferSyntaxUid(), path, size, hex(digest)));
                }

                manifest = new PackageManifest(PackageManifest.FORMAT, PackageManifest.VERSION, groupId, index, count,
                        study.studyInstanceUid(), createdAt.toString(), studyTotals,
                        series.entrySet().stream()
                                .map(e -> new PackageManifest.SeriesEntry(e.getKey(), e.getValue()))
                                .toList());
                byte[] json = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest);
                TarArchiveEntry entry = new TarArchiveEntry(PackageManifest.ENTRY_NAME);
                entry.setSize(json.length);
                entry.setLastModifiedTime(modTime);
                tar.putArchiveEntry(entry);
                tar.write(json);
                tar.closeArchiveEntry();
                tar.finish();
            }
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
        return new PackagePart(target, manifest, Files.size(target), hex(fileDigest));
    }

    private static void requireUid(String uid, String what) {
        if (!DicomUids.isValid(uid)) {
            throw new IllegalArgumentException(what + " invalide");
        }
    }

    static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponible", e);
        }
    }

    static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }
}
