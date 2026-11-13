package com.imagunity.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.luben.zstd.ZstdOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.tar.TarConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** Paquets malveillants, abîmés ou non conformes : ils doivent tous être refusés, sans laisser de fichier. */
class PackageReaderTest {

    private static final String SERIES = "2.25.7.1";
    private static final String SOP = "2.25.7.1.1";
    private static final byte[] DATA = "contenu DICOM synthétique".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private Path target;
    private final PackageReader reader = new PackageReader(1L << 20);
    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        target = dir.resolve("restored");
    }

    /** Une entrée d'archive : nom, contenu, type tar. */
    private record Entry(String name, byte[] data, byte type) {
        static Entry file(String name, byte[] data) {
            return new Entry(name, data, TarConstants.LF_NORMAL);
        }
    }

    private Path archive(List<Entry> entries) throws IOException {
        Path file = dir.resolve("paquet-" + System.nanoTime() + ".tar.zst");
        try (OutputStream os = Files.newOutputStream(file);
             ZstdOutputStream zstd = new ZstdOutputStream(os);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(zstd)) {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Entry e : entries) {
                TarArchiveEntry entry = new TarArchiveEntry(e.name(), e.type());
                entry.setSize(e.type() == TarConstants.LF_NORMAL ? e.data().length : 0);
                tar.putArchiveEntry(entry);
                if (e.type() == TarConstants.LF_NORMAL) {
                    tar.write(e.data());
                }
                tar.closeArchiveEntry();
            }
        }
        return file;
    }

    private PackageManifest manifest(String sha256, long size, String path) {
        var instance = new PackageManifest.InstanceEntry(SOP, "1.2", "1.2", path, size, sha256);
        return new PackageManifest(PackageManifest.FORMAT, PackageManifest.VERSION, "groupe", 1, 1, "2.25.7",
                "2026-11-11T12:00:00Z", new PackageManifest.Totals(1, size),
                List.of(new PackageManifest.SeriesEntry(SERIES, List.of(instance))));
    }

    private PackageManifest validManifest() {
        var digest = StudyPackager.sha256();
        digest.update(DATA);
        return manifest(StudyPackager.hex(digest), DATA.length, PackageManifest.entryPath(SERIES, SOP));
    }

    private Entry manifestEntry(PackageManifest manifest) {
        return Entry.file(PackageManifest.ENTRY_NAME, mapper.writeValueAsBytes(manifest));
    }

    private Entry instanceEntry() {
        return Entry.file(PackageManifest.entryPath(SERIES, SOP), DATA);
    }

    private void assertRejected(Path paquet, String message) {
        assertThatThrownBy(() -> reader.unpack(paquet, target))
                .isInstanceOf(PackageIntegrityException.class)
                .hasMessageContaining(message);
        assertNoFileLeft();
    }

    private void assertNoFileLeft() {
        if (Files.exists(target)) {
            try (Stream<Path> files = Files.walk(target)) {
                assertThat(files.filter(Files::isRegularFile)).as("fichiers laissés après refus").isEmpty();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void acceptsMinimalValidPackage() throws IOException {
        Path paquet = archive(List.of(instanceEntry(), manifestEntry(validManifest())));

        UnpackedPackage unpacked = reader.unpack(paquet, target);

        assertThat(unpacked.instances()).singleElement()
                .satisfies(i -> assertThat(i.file()).hasBinaryContent(DATA));
    }

    @Test
    void rejectsPathTraversal() throws IOException {
        assertRejected(archive(List.of(Entry.file("../../evil.dcm", DATA), manifestEntry(validManifest()))),
                "Nom d'entrée");
        assertRejected(archive(List.of(Entry.file("instances/../../evil.dcm", DATA))), "Nom d'entrée");
        assertThat(dir.resolve("evil.dcm")).doesNotExist();
    }

    @Test
    void rejectsUnexpectedEntryNames() throws IOException {
        assertRejected(archive(List.of(Entry.file("instances/2.25.7.1/notes.txt", DATA))), "Nom d'entrée");
        assertRejected(archive(List.of(Entry.file("instances/2.25.7.1/01.2.dcm", DATA))), "Nom d'entrée");
        assertRejected(archive(List.of(Entry.file("autre.json", DATA))), "Nom d'entrée");
    }

    @Test
    void rejectsOverlongUidInEntryName() throws IOException {
        String longUid = "1." + "1".repeat(70);
        assertRejected(archive(List.of(Entry.file("instances/" + longUid + "/1.2.dcm", DATA))), "Nom d'entrée");
        assertRejected(archive(List.of(Entry.file("instances/1.2/" + longUid + ".dcm", DATA))), "Nom d'entrée");
    }

    @Test
    void rejectsLinksAndDirectories() throws IOException {
        assertRejected(archive(List.of(new Entry("instances/2.25.7.1/", new byte[0], TarConstants.LF_DIR))),
                "non admise");
        // Nom valide : seul le type d'entrée (lien symbolique, lien physique, FIFO) doit provoquer le refus.
        String validName = PackageManifest.entryPath(SERIES, SOP);
        assertRejected(archive(List.of(new Entry(validName, new byte[0], TarConstants.LF_SYMLINK))), "non admise");
        assertRejected(archive(List.of(new Entry(validName, new byte[0], TarConstants.LF_LINK))), "non admise");
        assertRejected(archive(List.of(new Entry(validName, new byte[0], TarConstants.LF_FIFO))), "non admise");
    }

    @Test
    void rejectsDuplicateInstance() throws IOException {
        assertRejected(archive(List.of(instanceEntry(), instanceEntry(), manifestEntry(validManifest()))),
                "double");
    }

    @Test
    void rejectsEntriesAfterManifest() throws IOException {
        assertRejected(archive(List.of(manifestEntry(validManifest()), instanceEntry())), "après le manifeste");
    }

    @Test
    void rejectsMissingManifest() throws IOException {
        assertRejected(archive(List.of(instanceEntry())), "Manifeste absent");
    }

    @Test
    void rejectsUnreadableManifest() throws IOException {
        assertRejected(archive(List.of(instanceEntry(), Entry.file(PackageManifest.ENTRY_NAME, "{pas du json".getBytes(
                StandardCharsets.UTF_8)))), "Manifeste illisible");
    }

    @Test
    void rejectsUnsupportedFormatOrVersion() throws IOException {
        var m = validManifest();
        var otherFormat = new PackageManifest("autre", 1, m.packageGroupId(), 1, 1, m.studyInstanceUid(),
                m.createdAt(), m.studyTotals(), m.series());
        var otherVersion = new PackageManifest(PackageManifest.FORMAT, 2, m.packageGroupId(), 1, 1,
                m.studyInstanceUid(), m.createdAt(), m.studyTotals(), m.series());

        assertRejected(archive(List.of(instanceEntry(), manifestEntry(otherFormat))), "Format");
        assertRejected(archive(List.of(instanceEntry(), manifestEntry(otherVersion))), "Format");
    }

    @Test
    void rejectsInconsistentPartNumbering() throws IOException {
        var m = validManifest();
        var zero = new PackageManifest(m.format(), m.version(), m.packageGroupId(), 0, 1, m.studyInstanceUid(),
                m.createdAt(), m.studyTotals(), m.series());
        var beyond = new PackageManifest(m.format(), m.version(), m.packageGroupId(), 3, 2, m.studyInstanceUid(),
                m.createdAt(), m.studyTotals(), m.series());

        assertRejected(archive(List.of(instanceEntry(), manifestEntry(zero))), "Numérotation");
        assertRejected(archive(List.of(instanceEntry(), manifestEntry(beyond))), "Numérotation");
    }

    @Test
    void rejectsInvalidStudyUidInManifest() throws IOException {
        var m = validManifest();
        var bad = new PackageManifest(m.format(), m.version(), m.packageGroupId(), 1, 1, "../x",
                m.createdAt(), m.studyTotals(), m.series());

        assertRejected(archive(List.of(instanceEntry(), manifestEntry(bad))), "Study Instance UID");
    }

    @Test
    void rejectsAlteredInstance() throws IOException {
        byte[] altered = DATA.clone();
        altered[0] ^= 1;
        assertRejected(archive(List.of(Entry.file(PackageManifest.entryPath(SERIES, SOP), altered),
                manifestEntry(validManifest()))), "altérée");
    }

    @Test
    void rejectsWrongSize() throws IOException {
        var m = validManifest();
        var wrongSize = manifest(m.instances().findFirst().orElseThrow().sha256(), DATA.length + 1,
                PackageManifest.entryPath(SERIES, SOP));

        assertRejected(archive(List.of(instanceEntry(), manifestEntry(wrongSize))), "altérée");
    }

    @Test
    void rejectsInconsistentManifestPath() throws IOException {
        var m = validManifest();
        var wrongPath = manifest(m.instances().findFirst().orElseThrow().sha256(), DATA.length,
                "instances/9.9/" + SOP + ".dcm");

        assertRejected(archive(List.of(instanceEntry(), manifestEntry(wrongPath))), "Chemin incohérent");
    }

    @Test
    void rejectsInstanceMissingFromPackage() throws IOException {
        assertRejected(archive(List.of(manifestEntry(validManifest()))), "absente du paquet");
    }

    @Test
    void rejectsInstanceMissingFromManifest() throws IOException {
        Entry extra = Entry.file(PackageManifest.entryPath(SERIES, "2.25.7.1.2"), DATA);

        assertRejected(archive(List.of(instanceEntry(), extra, manifestEntry(validManifest()))),
                "absente du manifeste");
    }

    @Test
    void rejectsAnInstanceListedTwiceInPlaceOfAnother() throws IOException {
        // Deux instances dans le paquet ; le manifeste liste la première deux fois et omet la seconde.
        PackageManifest valid = validManifest();
        var listed = valid.series().getFirst().instances().getFirst();
        PackageManifest twice = new PackageManifest(valid.format(), valid.version(), valid.packageGroupId(),
                valid.partIndex(), valid.partCount(), valid.studyInstanceUid(), valid.createdAt(), valid.studyTotals(),
                List.of(new PackageManifest.SeriesEntry(SERIES, List.of(listed, listed))));
        Entry unlisted = Entry.file(PackageManifest.entryPath(SERIES, "2.25.7.1.2"), DATA);

        assertRejected(archive(List.of(instanceEntry(), unlisted, manifestEntry(twice))), "listée deux fois");
    }

    @Test
    void rejectsDecompressionBomb() throws IOException {
        PackageReader strict = new PackageReader(DATA.length - 1);
        Path paquet = archive(List.of(instanceEntry(), manifestEntry(validManifest())));

        assertThatThrownBy(() -> strict.unpack(paquet, target))
                .isInstanceOf(PackageIntegrityException.class)
                .hasMessageContaining("Volume");
        assertNoFileLeft();
    }

    @Test
    void rejectsOversizedManifest() throws IOException {
        byte[] huge = new byte[PackageReader.MAX_MANIFEST_SIZE + 1];
        assertRejected(archive(List.of(Entry.file(PackageManifest.ENTRY_NAME, huge))), "trop volumineux");
    }

    @Test
    void rejectsCorruptedFile() throws IOException {
        Path paquet = archive(List.of(instanceEntry(), manifestEntry(validManifest())));
        byte[] bytes = Files.readAllBytes(paquet);
        bytes[bytes.length / 2] ^= (byte) 0xFF;
        Files.write(paquet, bytes);

        assertRejected(paquet, "");
    }

    @Test
    void rejectsNonZstdFile() throws IOException {
        Path paquet = Files.writeString(dir.resolve("faux.tar.zst"), "ceci n'est pas un paquet");

        assertRejected(paquet, "Paquet illisible");
    }

    @Test
    void refusesToOverwriteExistingFiles() throws IOException {
        Path paquet = archive(List.of(instanceEntry(), manifestEntry(validManifest())));
        reader.unpack(paquet, target);

        assertThatThrownBy(() -> reader.unpack(paquet, target)).isInstanceOf(PackageIntegrityException.class);
    }

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new PackageReader(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
