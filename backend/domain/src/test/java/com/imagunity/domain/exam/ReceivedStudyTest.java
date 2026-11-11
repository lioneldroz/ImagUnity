package com.imagunity.domain.exam;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReceivedStudyTest {

    private static ReceivedInstance instance(String sop, String series, long size) {
        return new ReceivedInstance("1.2.840.10008.5.1.4.1.1.2", sop, series, "1.2.840.10008.1.2.1",
                Path.of(sop + ".dcm"), size);
    }

    private static ReceivedStudy study(List<ReceivedInstance> instances) {
        Instant t = Instant.parse("2026-11-11T10:00:00Z");
        return new ReceivedStudy("2.25.1", "IU_TO_B", "box-b", Set.of("ORTHANC_A"), instances, t, t.plusSeconds(5));
    }

    @Test
    void countsSeriesAndSize() {
        ReceivedStudy study = study(List.of(
                instance("2.25.1.1.1", "2.25.1.1", 100),
                instance("2.25.1.1.2", "2.25.1.1", 200),
                instance("2.25.1.2.1", "2.25.1.2", 300)));

        assertThat(study.seriesCount()).isEqualTo(2);
        assertThat(study.totalSize()).isEqualTo(600);
    }

    @Test
    void emptyStudy() {
        ReceivedStudy study = study(List.of());

        assertThat(study.seriesCount()).isZero();
        assertThat(study.totalSize()).isZero();
    }

    @Test
    void collectionsAreDefensivelyCopied() {
        List<ReceivedInstance> instances = new ArrayList<>(List.of(instance("2.25.1.1.1", "2.25.1.1", 1)));
        Set<String> calling = new HashSet<>(Set.of("ORTHANC_A"));
        Instant t = Instant.now();
        ReceivedStudy study = new ReceivedStudy("2.25.1", "IU_TO_B", "box-b", calling, instances, t, t);

        instances.clear();
        calling.clear();

        assertThat(study.instances()).hasSize(1);
        assertThat(study.callingAets()).containsExactly("ORTHANC_A");
        assertThatThrownBy(() -> study.instances().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void requiredFields() {
        Instant t = Instant.now();
        assertThatThrownBy(() -> new ReceivedStudy(null, "IU_TO_B", "box-b", Set.of(), List.of(), t, t))
                .isInstanceOf(NullPointerException.class).hasMessage("studyInstanceUid");
        assertThatThrownBy(() -> new ReceivedStudy("2.25.1", null, "box-b", Set.of(), List.of(), t, t))
                .isInstanceOf(NullPointerException.class).hasMessage("calledAet");
        assertThatThrownBy(() -> new ReceivedStudy("2.25.1", "IU_TO_B", null, Set.of(), List.of(), t, t))
                .isInstanceOf(NullPointerException.class).hasMessage("destination");
    }

    @Test
    void instanceRequiredFields() {
        Path file = Path.of("x.dcm");
        assertThatThrownBy(() -> new ReceivedInstance("1.2", null, "2.25.1", "1.2", file, 1))
                .isInstanceOf(NullPointerException.class).hasMessage("sopInstanceUid");
        assertThatThrownBy(() -> new ReceivedInstance("1.2", "2.25.1.1", null, "1.2", file, 1))
                .isInstanceOf(NullPointerException.class).hasMessage("seriesInstanceUid");
        assertThatThrownBy(() -> new ReceivedInstance("1.2", "2.25.1.1", "2.25.1", "1.2", null, 1))
                .isInstanceOf(NullPointerException.class).hasMessage("file");
    }
}
