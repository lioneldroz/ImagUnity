package com.imagunity.packaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Allers-retours empaquetage → dépaquetage : l'étude doit ressortir identique. */
class StudyPackagerTest {

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private final PackageReader reader = new PackageReader(1L << 30);

    private Path in() throws IOException {
        return Files.createDirectories(dir.resolve("in"));
    }

    private Path out() {
        return dir.resolve("out");
    }

    @Test
    void roundTripRestoresEveryInstanceByteForByte() throws IOException {
        ReceivedStudy study = TestStudies.study(in(), 2, 3, 20_000, false);

        List<PackagePart> parts = new StudyPackager(PackagingConfig.defaults(), clock).pack(study, out());

        assertThat(parts).hasSize(1);
        PackagePart part = parts.getFirst();
        assertThat(part.file()).exists().hasFileName(part.manifest().packageGroupId() + "-1-of-1.tar.zst");
        assertThat(part.size()).isEqualTo(Files.size(part.file()));
        assertThat(part.sha256()).isEqualTo(sha256(part.file()));

        UnpackedPackage unpacked = reader.unpack(part.file(), dir.resolve("restored"));

        assertThat(unpacked.sha256()).isEqualTo(part.sha256());
        assertThat(unpacked.manifest()).isEqualTo(part.manifest());
        assertThat(unpacked.instances()).hasSize(6);
        for (ReceivedInstance original : study.instances()) {
            UnpackedPackage.Instance restored = unpacked.instances().stream()
                    .filter(i -> i.entry().sopInstanceUid().equals(original.sopInstanceUid()))
                    .findFirst().orElseThrow();
            assertThat(restored.seriesInstanceUid()).isEqualTo(original.seriesInstanceUid());
            assertThat(restored.file()).hasSameBinaryContentAs(original.file());
            assertThat(restored.entry().sha256()).isEqualTo(sha256(original.file()));
        }
    }

    @Test
    void manifestDescribesTheStudy() throws IOException {
        ReceivedStudy study = TestStudies.study(in(), 2, 2, 1000, true);

        PackageManifest manifest = new StudyPackager(PackagingConfig.defaults(), clock)
                .pack(study, out()).getFirst().manifest();

        assertThat(manifest.format()).isEqualTo("imagunity-package");
        assertThat(manifest.version()).isEqualTo(1);
        assertThat(manifest.studyInstanceUid()).isEqualTo(TestStudies.STUDY);
        assertThat(manifest.createdAt()).isEqualTo("2026-11-11T12:00:00Z");
        assertThat(manifest.partIndex()).isEqualTo(1);
        assertThat(manifest.partCount()).isEqualTo(1);
        assertThat(manifest.studyTotals()).isEqualTo(new PackageManifest.Totals(4, 4000));
        assertThat(manifest.partTotals()).isEqualTo(manifest.studyTotals());
        assertThat(manifest.series()).extracting(PackageManifest.SeriesEntry::seriesInstanceUid)
                .containsExactly("2.25.7.1", "2.25.7.2");
        assertThat(manifest.instances()).allSatisfy(i -> {
            assertThat(i.sopClassUid()).isEqualTo("1.2.840.10008.5.1.4.1.1.2");
            assertThat(i.transferSyntaxUid()).isEqualTo("1.2.840.10008.1.2.1");
            assertThat(i.sha256()).hasSize(64);
            assertThat(i.path()).startsWith("instances/2.25.7.").endsWith(i.sopInstanceUid() + ".dcm");
        });
    }

    @Test
    void compressesWithoutLoss() throws IOException {
        ReceivedStudy study = TestStudies.study(in(), 1, 4, 100_000, true);

        PackagePart part = new StudyPackager(PackagingConfig.defaults(), clock).pack(study, out()).getFirst();

        assertThat(part.size()).isLessThan(study.totalSize() / 10);
        assertThat(reader.unpack(part.file(), dir.resolve("restored")).instances()).hasSize(4);
    }

    @Test
    void prioritySeriesArePackedFirst() throws IOException {
        ReceivedStudy study = TestStudies.study(in(), 3, 2, 1_000, false);
        String last = study.instances().getLast().seriesInstanceUid();
        StudyPackager packager = new StudyPackager(PackagingConfig.defaults(),
                series -> series.getFirst().seriesInstanceUid().equals(last) ? 1 : 5);

        List<PackagePart> parts = packager.pack(study, out());

        assertThat(parts).hasSize(2);
        assertThat(parts.getFirst().manifest().series()).as("série prioritaire seule dans le premier paquet")
                .singleElement().satisfies(s -> assertThat(s.seriesInstanceUid()).isEqualTo(last));
        assertThat(parts.get(1).manifest().series()).hasSize(2);
    }

    @Test
    void largeStudyIsSplitBySeries() throws IOException {
        ReceivedStudy study = TestStudies.study(in(), 4, 5, 10_000, false); // 4 séries de 50 000 octets
        StudyPackager packager = new StudyPackager(new PackagingConfig(120_000, 3), clock);

        List<PackagePart> parts = packager.pack(study, out());

        assertThat(parts).hasSize(2);
        assertThat(parts).extracting(PackagePart::index).containsExactly(1, 2);
        assertThat(parts).allSatisfy(p -> {
            assertThat(p.count()).isEqualTo(2);
            assertThat(p.manifest().series()).hasSize(2);
            assertThat(p.manifest().studyTotals()).isEqualTo(new PackageManifest.Totals(20, 200_000));
            assertThat(p.manifest().packageGroupId()).isEqualTo(parts.getFirst().manifest().packageGroupId());
        });

        // Tous les paquets réunis redonnent l'étude entière, chaque instance une seule fois.
        int restored = 0;
        for (PackagePart part : parts) {
            restored += reader.unpack(part.file(), dir.resolve("restored")).instances().size();
        }
        assertThat(restored).isEqualTo(20);
        try (Stream<Path> files = Files.walk(dir.resolve("restored"))) {
            assertThat(files.filter(Files::isRegularFile).count()).isEqualTo(20);
        }
    }

    @Test
    void outputContainsOnlyFinishedPackages() throws IOException {
        ReceivedStudy study = TestStudies.study(in(), 1, 2, 100, true);

        new StudyPackager(PackagingConfig.defaults(), clock).pack(study, out());

        try (Stream<Path> files = Files.list(out())) {
            assertThat(files).singleElement().satisfies(f -> assertThat(f.toString()).endsWith(".tar.zst"));
        }
    }

    @Test
    void failureLeavesNoPartialPackage() throws IOException {
        ReceivedStudy study = TestStudies.study(in(), 3, 1, 100, true);
        ReceivedInstance missing = study.instances().get(2);
        Files.delete(missing.file()); // fichier disparu : le 3e paquet échoue
        StudyPackager packager = new StudyPackager(new PackagingConfig(100, 3), clock);

        assertThatThrownBy(() -> packager.pack(study, out())).isInstanceOf(IOException.class);

        try (Stream<Path> files = Files.list(out())) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void actualFileSizeWinsOverRecordedSize() throws IOException {
        ReceivedInstance instance = TestStudies.instance(in(), "2.25.7.1", "2.25.7.1.1", 100, true);
        // Taille enregistrée à la réception différente de la taille réelle : c'est le fichier qui fait foi.
        ReceivedInstance stale = new ReceivedInstance(instance.sopClassUid(), instance.sopInstanceUid(),
                instance.seriesInstanceUid(), instance.transferSyntaxUid(), instance.file(), 999);

        PackagePart part = new StudyPackager(PackagingConfig.defaults(), clock)
                .pack(TestStudies.study(List.of(stale)), out()).getFirst();

        assertThat(part.manifest().instances().findFirst().orElseThrow().size()).isEqualTo(100);
        assertThat(part.manifest().studyTotals().bytes()).isEqualTo(100);
        assertThat(reader.unpack(part.file(), dir.resolve("restored")).instances()).hasSize(1);
    }

    @Test
    void rejectsEmptyStudy() {
        ReceivedStudy empty = TestStudies.study(List.of());

        assertThatThrownBy(() -> new StudyPackager(PackagingConfig.defaults()).pack(empty, out()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidUids() throws IOException {
        Path file = Files.writeString(in().resolve("x.dcm"), "x");
        Instant now = Instant.now();
        var badStudy = new ReceivedStudy("../x", "IU_TO_B", "box-b", Set.of(),
                List.of(new ReceivedInstance("1.2", "2.25.1.1", "2.25.1", "1.2", file, 1)), now, now);
        var badSeries = new ReceivedStudy("2.25.1", "IU_TO_B", "box-b", Set.of(),
                List.of(new ReceivedInstance("1.2", "2.25.1.1", "a/b", "1.2", file, 1)), now, now);
        var badSop = new ReceivedStudy("2.25.1", "IU_TO_B", "box-b", Set.of(),
                List.of(new ReceivedInstance("1.2", "..", "2.25.1", "1.2", file, 1)), now, now);
        StudyPackager packager = new StudyPackager(PackagingConfig.defaults());

        assertThatThrownBy(() -> packager.pack(badStudy, out())).hasMessageContaining("Study");
        assertThatThrownBy(() -> packager.pack(badSeries, out())).hasMessageContaining("Series");
        assertThatThrownBy(() -> packager.pack(badSop, out())).hasMessageContaining("SOP");
    }

    static String sha256(Path file) throws IOException {
        var digest = StudyPackager.sha256();
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }
}
