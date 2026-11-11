package com.imagunity.dicom;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudyAssemblerTest {

    /** Horloge réglable à la main. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-11T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }
    }

    private final TestClock clock = new TestClock();
    private final List<ReceivedStudy> completed = new ArrayList<>();
    private final StudyAssembler assembler = new StudyAssembler(clock, Duration.ofSeconds(30), completed::add);

    private static ReceivedInstance instance(String sop, String series) {
        return new ReceivedInstance("1.2.840.10008.5.1.4.1.1.2", sop, series, "1.2.840.10008.1.2.1",
                Path.of(sop + ".dcm"), 100);
    }

    @Test
    void studyIsCompleteAfterIdleTimeout() {
        assembler.add("IU_TO_B", "box-b", "ORTHANC_A", "1.1", instance("1.1.1.1", "1.1.1"));
        clock.advance(Duration.ofSeconds(10));
        assembler.add("IU_TO_B", "box-b", "ORTHANC_A", "1.1", instance("1.1.1.2", "1.1.1"));
        clock.advance(Duration.ofSeconds(29));
        assembler.flushIdle();
        assertThat(completed).isEmpty();

        clock.advance(Duration.ofSeconds(1));
        assembler.flushIdle();

        assertThat(completed).hasSize(1);
        ReceivedStudy study = completed.getFirst();
        assertThat(study.studyInstanceUid()).isEqualTo("1.1");
        assertThat(study.destination()).isEqualTo("box-b");
        assertThat(study.instances()).hasSize(2);
        assertThat(study.totalSize()).isEqualTo(200);
        assertThat(study.lastReceivedAt()).isEqualTo(study.firstReceivedAt().plusSeconds(10));
        assertThat(assembler.pendingStudies()).isZero();
    }

    @Test
    void duplicateInstanceIsCountedOnce() {
        assembler.add("IU_TO_B", "box-b", "ORTHANC_A", "1.1", instance("1.1.1.1", "1.1.1"));
        assembler.add("IU_TO_B", "box-b", "ORTHANC_A", "1.1", instance("1.1.1.1", "1.1.1"));
        clock.advance(Duration.ofSeconds(30));
        assembler.flushIdle();

        assertThat(completed.getFirst().instances()).hasSize(1);
    }

    @Test
    void sameStudyToTwoDestinationsGivesTwoStudies() {
        assembler.add("IU_TO_B", "box-b", "ORTHANC_A", "1.1", instance("1.1.1.1", "1.1.1"));
        assembler.add("IU_TO_C", "box-c", "ORTHANC_A", "1.1", instance("1.1.1.1", "1.1.1"));
        clock.advance(Duration.ofSeconds(30));
        assembler.flushIdle();

        assertThat(completed).extracting(ReceivedStudy::destination).containsExactlyInAnyOrder("box-b", "box-c");
    }

    @Test
    void countsSeriesAndCollectsCallingAets() {
        assembler.add("IU_TO_B", "box-b", "ORTHANC_A", "1.1", instance("1.1.1.1", "1.1.1"));
        assembler.add("IU_TO_B", "box-b", "STATION_2", "1.1", instance("1.1.2.1", "1.1.2"));
        assembler.add("IU_TO_B", "box-b", null, "1.1", instance("1.1.2.2", "1.1.2"));
        clock.advance(Duration.ofSeconds(30));
        assembler.flushIdle();

        ReceivedStudy study = completed.getFirst();
        assertThat(study.seriesCount()).isEqualTo(2);
        assertThat(study.callingAets()).containsExactlyInAnyOrder("ORTHANC_A", "STATION_2");
    }

    @Test
    void failingListenerDoesNotBlockOtherStudies() {
        List<String> seen = new ArrayList<>();
        StudyAssembler failing = new StudyAssembler(clock, Duration.ofSeconds(30), s -> {
            seen.add(s.studyInstanceUid());
            throw new IllegalStateException("panne simulée");
        });
        failing.add("IU_TO_B", "box-b", "ORTHANC_A", "1.1", instance("1.1.1.1", "1.1.1"));
        failing.add("IU_TO_B", "box-b", "ORTHANC_A", "1.2", instance("1.2.1.1", "1.2.1"));
        clock.advance(Duration.ofSeconds(30));
        failing.flushIdle();

        assertThat(seen).containsExactlyInAnyOrder("1.1", "1.2");
    }
}
