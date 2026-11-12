package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Règles de priorité en vigueur : propriétés, ou fichier JSON rechargé à chaud. */
class ReloadablePriorityTest {

    @TempDir
    Path dir;

    private final Clock clock = Clock.systemUTC();

    private static final String THORAX = """
            { "default": 4,
              "rules": [ { "name": "scanner-thorax", "priority": 1,
                           "and": { "Modality": "CT", "SeriesDescription": "*THORAX*" } },
                         { "name": "scouts", "priority": 9, "or": { "ImageType": "*LOCALIZER*" } } ] }
            """;

    private void write(Path file, String content, long seconds) throws Exception {
        Files.writeString(file, content);
        Files.setLastModifiedTime(file, FileTime.from(Instant.ofEpochSecond(1_800_000_000L + seconds)));
    }

    @Test
    void rulesFromThePropertiesAreFixed() {
        ReloadablePriority none = ReloadablePriority.of(new PriorityProperties(5, null, List.of()), clock);
        assertThat(none.state().source()).isEqualTo("aucune règle");
        assertThat(none.state().rules().rules()).isEmpty();
        assertThat(none.of(List.of())).isEqualTo(5);

        ReloadablePriority configured = ReloadablePriority.of(new PriorityProperties(6, Path.of(" "), List.of(
                new PriorityProperties.Rule("irm", 2, Map.of("Modality", "MR"), null))), clock);
        assertThat(configured.state().source()).isEqualTo("configuration");
        assertThat(configured.state().rules().defaultPriority()).isEqualTo(6);
        assertThat(configured.state().error()).isNull();

        assertThatThrownBy(() -> ReloadablePriority.of(new PriorityProperties(5, null, List.of(
                new PriorityProperties.Rule("x", 1, Map.of("Modalite", "MR"), null))), clock))
                .hasMessageContaining("Modalite");
    }

    @Test
    void aFileIsReloadedWhenItChangesAndAnInvalidChangeIsRefused() throws Exception {
        Path file = dir.resolve("priorite.json");
        write(file, THORAX, 0);
        ReloadablePriority priority = ReloadablePriority.of(new PriorityProperties(5, file, List.of()), clock);
        assertThat(priority.state().source()).isEqualTo("fichier " + file);
        assertThat(priority.state().rules().defaultPriority()).isEqualTo(4);
        assertThat(priority.state().rules().describe()).hasSize(2);

        write(file, "{ \"rules\": [ { \"name\": \"irm\", \"priority\": 2, \"and\": { \"Modality\": \"MR\" } } ] }", 10);
        assertThat(priority.state().rules().describe()).containsExactly("irm : priorité 2 (Modality=MR)");
        assertThat(priority.state().rules().defaultPriority()).as("défaut du document absent : 5").isEqualTo(5);

        write(file, "{ \"rules\": [ { \"name\": \"x\", \"priority\": 1, \"and\": { \"Modalite\": \"MR\" } } ] }", 20);
        ReloadablePriority.State refused = priority.state();
        assertThat(refused.rules().describe()).as("précédentes en vigueur").containsExactly("irm : priorité 2 (Modality=MR)");
        assertThat(refused.error()).contains("Modalite");
        assertThat(priority.state().error()).as("même version : pas relue").contains("Modalite");

        write(file, "{ \"inattendu\": 1 }", 30);
        assertThat(priority.state().error()).contains("JSON invalide");
        Files.delete(file);
        assertThat(priority.state().error()).contains("illisible");
        assertThat(priority.of(List.of())).isEqualTo(5);
    }

    @Test
    void anInvalidOrMissingFilePreventsTheStart() throws Exception {
        Path file = dir.resolve("faux.json");
        write(file, "{ \"rules\": [ { \"name\": \"x\", \"priority\": 12 } ] }", 0);
        assertThatThrownBy(() -> ReloadablePriority.of(new PriorityProperties(5, file, List.of()), clock))
                .hasMessageContaining("invalides").hasMessageContaining("hors de 1 à 9");
        assertThatThrownBy(() -> ReloadablePriority.of(new PriorityProperties(5, dir.resolve("absent.json"),
                List.of()), clock)).hasMessageContaining("illisibles");
        assertThat(ReloadablePriority.parse("{}").rules()).isEmpty();
    }
}
