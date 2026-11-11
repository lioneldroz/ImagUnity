package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DicomInputConfigTest {

    private static DicomInputConfig config(Set<String> calling, Map<String, String> routes) {
        return new DicomInputConfig(null, 11112, calling, routes, Path.of("incoming"), Duration.ofSeconds(30), 0);
    }

    @Test
    void routesCalledAetToDestination() {
        var config = config(Set.of("ORTHANC_A"), Map.of("IU_TO_B", "box-b"));

        assertThat(config.destinationFor("IU_TO_B")).contains("box-b");
        assertThat(config.destinationFor("IU_TO_B  ")).contains("box-b");
        assertThat(config.destinationFor("IU_TO_C")).isEmpty();
        assertThat(config.destinationFor(null)).isEmpty();
    }

    @Test
    void onlyListedCallingAetsAreAllowed() {
        var config = config(Set.of("ORTHANC_A"), Map.of("IU_TO_B", "box-b"));

        assertThat(config.isCallingAetAllowed("ORTHANC_A")).isTrue();
        assertThat(config.isCallingAetAllowed("orthanc_a")).isFalse();
        assertThat(config.isCallingAetAllowed("INCONNU")).isFalse();
        assertThat(config.isCallingAetAllowed(null)).isFalse();
    }

    @Test
    void emptyAllowListRejectsEveryone() {
        assertThat(config(Set.of(), Map.of("IU_TO_B", "box-b")).isCallingAetAllowed("ORTHANC_A")).isFalse();
    }

    @Test
    void rejectsAetsUnsafeAsDirectoryNames() {
        assertThatThrownBy(() -> config(Set.of(), Map.of("../X", "box-b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(Set.of(), Map.of("AET TROP LONG POUR DICOM", "box-b")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config(Set.of("A B"), Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsRouteWithoutDestination() {
        assertThatThrownBy(() -> config(Set.of(), Map.of("IU_TO_B", " ")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveIdleTimeout() {
        assertThatThrownBy(() -> new DicomInputConfig(null, 11112, Set.of(), Map.of(), Path.of("x"), Duration.ZERO, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DicomInputConfig(null, 11112, Set.of(), Map.of(), Path.of("x"),
                Duration.ofSeconds(-1), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidPort() {
        assertThatThrownBy(() -> new DicomInputConfig(null, 0, Set.of(), Map.of(), Path.of("x"), Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Port");
        assertThatThrownBy(() -> new DicomInputConfig(null, 65536, Set.of(), Map.of(), Path.of("x"),
                Duration.ofSeconds(1), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Port");
    }

    @Test
    void requiresStorageDirAndTimeout() {
        assertThatThrownBy(() -> new DicomInputConfig(null, 11112, Set.of(), Map.of(), null, Duration.ofSeconds(1), 0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DicomInputConfig(null, 11112, Set.of(), Map.of(), Path.of("x"), null, 0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullAet() {
        var routes = new java.util.HashMap<String, String>();
        routes.put("IU_TO_B", null);
        assertThatThrownBy(() -> config(Set.of(), routes)).isInstanceOf(NullPointerException.class);
    }
}
