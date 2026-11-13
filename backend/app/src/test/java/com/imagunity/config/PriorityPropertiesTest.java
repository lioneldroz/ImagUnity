package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.configuration.ReloadablePriority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Règles de priorité d'après les propriétés de la box (données synthétiques). */
class PriorityPropertiesTest {

    @TempDir
    Path dir;

    private final Clock clock = Clock.systemUTC();

    @Test
    void rulesComeFromThePropertiesOrFromAFile() throws Exception {
        ReloadablePriority configured = new PriorityProperties(6, Path.of(" "), List.of(
                new PriorityProperties.Rule("irm", 2, Map.of("Modality", "MR"), null))).priority(clock);
        assertThat(configured.state().source()).isEqualTo("configuration");
        assertThat(configured.state().rules().defaultPriority()).isEqualTo(6);
        assertThat(configured.state().rules().describe()).containsExactly("irm : priorité 2 (Modality=MR)");

        assertThatThrownBy(() -> new PriorityProperties(5, null, List.of(
                new PriorityProperties.Rule("x", 1, Map.of("Modalite", "MR"), null))).priority(clock))
                .hasMessageContaining("Modalite");

        Path file = Files.writeString(dir.resolve("priorite.json"), "{ \"default\": 3, \"rules\": [] }");
        ReloadablePriority fromFile = new PriorityProperties(5, file, List.of()).priority(clock);
        assertThat(fromFile.state().source()).isEqualTo("fichier " + file);
        assertThat(fromFile.state().rules().defaultPriority()).isEqualTo(3);
    }
}
