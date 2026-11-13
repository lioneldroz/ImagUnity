package com.imagunity.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionRegistryTest {

    @TempDir
    Path dir;

    private static final Instant NOW = Instant.parse("2026-11-13T10:00:00Z");

    @Test
    void numbersFollowEachOtherAndVersionsOnlyGoForward() throws IOException {
        VersionRegistry empty = new VersionRegistry("imagunity-box", 30, "essais avant 30", null);
        assertThat(empty.versions()).isEmpty();
        assertThat(empty.nextSequence()).isEqualTo(30);
        assertThat(empty.last()).isEmpty();
        empty.checkNew("imagunity-box", "0.15.0", "aa");

        VersionRegistry one = empty.with(new VersionRegistry.Entry("0.15.0", 30, "aa", NOW));
        assertThat(one.nextSequence()).isEqualTo(31);
        assertThat(one.last()).hasValueSatisfying(e -> assertThat(e.version()).isEqualTo("0.15.0"));
        one.checkNew("imagunity-box", "0.15.1", "bb");
        one.checkNew("imagunity-box", "0.16.0", "bb");
        one.checkNew("imagunity-box", "1.0.0", "bb");

        assertThatThrownBy(() -> one.checkNew("autre-produit", "0.16.0", "bb")).hasMessageContaining("autre-produit");
        assertThatThrownBy(() -> one.checkNew("imagunity-box", "0.16.0-SNAPSHOT", "bb")).hasMessageContaining("SNAPSHOT");
        assertThatThrownBy(() -> one.checkNew("imagunity-box", "0.16", "bb")).hasMessageContaining("non livrable");
        assertThatThrownBy(() -> one.checkNew("imagunity-box", "0.15.0", "bb")).hasMessageContaining("déjà signée");
        assertThatThrownBy(() -> one.checkNew("imagunity-box", "0.16.0", "AA")).hasMessageContaining("Artefact déjà signé");
        assertThatThrownBy(() -> one.checkNew("imagunity-box", "0.14.9", "bb")).hasMessageContaining("pas plus récente");
        assertThatThrownBy(() -> one.with(new VersionRegistry.Entry("0.15.1", 31, "bb", NOW))
                .checkNew("imagunity-box", "0.15.1", "cc")).hasMessageContaining("déjà signée");
    }

    @Test
    void versionsCompareNumerically() {
        assertThat(VersionRegistry.compare("0.10.0", "0.9.9")).isPositive();
        assertThat(VersionRegistry.compare("1.0.0", "1.0.0")).isZero();
        assertThat(VersionRegistry.compare("1.2.3", "1.2.10")).isNegative();
        assertThatThrownBy(() -> VersionRegistry.compare("1.0", "1.0.0")).hasMessageContaining("comparables");
        assertThatThrownBy(() -> VersionRegistry.compare("1.0.0", "x")).hasMessageContaining("comparables");
    }

    @Test
    void theRegistryIsReadAndWrittenAsReadableJson() throws IOException {
        Path file = dir.resolve("registre.json");
        new VersionRegistry("imagunity-box", 30, "note", List.of(new VersionRegistry.Entry("0.15.0", 30, "aa", NOW)))
                .write(file);
        assertThat(Files.readString(file)).contains("\n  \"product\"").contains("\"signedAt\" : \"2026-11-13T10:00:00Z\"");
        VersionRegistry read = VersionRegistry.read(file);
        assertThat(read.versions()).singleElement().satisfies(e -> assertThat(e.sequence()).isEqualTo(30));
        assertThat(dir.resolve("registre.json.nouveau")).doesNotExist();

        Files.writeString(file, "{\"product\":\"imagunity-box\",\"firstSequence\":30,\"inconnu\":1}");
        assertThatThrownBy(() -> VersionRegistry.read(file)).hasMessageContaining("illisible");
        Files.writeString(file, "null");
        assertThatThrownBy(() -> VersionRegistry.read(file)).hasMessageContaining("vide");
        assertThatThrownBy(() -> new VersionRegistry("imagunity-box", 0, null, null)).hasMessageContaining("premier");
    }

    @Test
    void theVersionIsReadFromTheJarManifest() throws IOException {
        assertThat(VersionRegistry.jarVersion(ReleaseToolTest.jarWithVersion(dir.resolve("a.jar"), "0.15.0")))
                .hasValue("0.15.0");
        assertThat(VersionRegistry.jarVersion(ReleaseToolTest.jarWithVersion(dir.resolve("b.jar"), null))).isEmpty();
        assertThat(VersionRegistry.jarVersion(ReleaseToolTest.jarWithVersion(dir.resolve("c.jar"), " "))).isEmpty();
        assertThat(VersionRegistry.jarVersion(Files.writeString(dir.resolve("d.jar"), "pas un jar"))).isEmpty();
    }
}
