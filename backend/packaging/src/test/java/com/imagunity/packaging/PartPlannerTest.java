package com.imagunity.packaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.domain.exam.ReceivedInstance;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartPlannerTest {

    private static ReceivedInstance instance(String series, int n, long size) {
        return new ReceivedInstance("1.2", series + "." + n, series, "1.2", Path.of(n + ".dcm"), size);
    }

    private static List<ReceivedInstance> series(String uid, int count, long size) {
        List<ReceivedInstance> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            list.add(instance(uid, i, size));
        }
        return list;
    }

    private static List<String> seriesOf(List<ReceivedInstance> part) {
        return part.stream().map(ReceivedInstance::seriesInstanceUid).distinct().toList();
    }

    @Test
    void smallStudyFitsInOnePart() {
        List<ReceivedInstance> all = new ArrayList<>(series("1.1", 3, 10));
        all.addAll(series("1.2", 2, 10));

        var parts = new PartPlanner(1000).plan(all);

        assertThat(parts).hasSize(1);
        assertThat(parts.getFirst()).hasSize(5);
    }

    @Test
    void seriesAreNotSplitWhenTheyFit() {
        List<ReceivedInstance> all = new ArrayList<>(series("1.1", 6, 10)); // 60
        all.addAll(series("1.2", 6, 10));                                    // 60
        all.addAll(series("1.3", 3, 10));                                    // 30

        var parts = new PartPlanner(100).plan(all);

        assertThat(parts).hasSize(2);
        assertThat(seriesOf(parts.get(0))).containsExactly("1.1");
        assertThat(seriesOf(parts.get(1))).containsExactly("1.2", "1.3");
    }

    @Test
    void oversizedSeriesIsSplitBetweenInstances() {
        List<ReceivedInstance> all = new ArrayList<>(series("1.1", 2, 10));  // 20
        all.addAll(series("1.2", 25, 10));                                    // 250 > 100

        var parts = new PartPlanner(100).plan(all);

        assertThat(parts).allSatisfy(p -> assertThat(p.stream().mapToLong(ReceivedInstance::size).sum())
                .isLessThanOrEqualTo(100));
        assertThat(parts.stream().mapToInt(List::size).sum()).isEqualTo(27);
        assertThat(parts.getFirst()).hasSize(10); // 2 de la série 1.1, puis 8 de la série 1.2
    }

    @Test
    void oversizedInstanceGetsItsOwnPart() {
        List<ReceivedInstance> all = List.of(instance("1.1", 1, 10), instance("1.2", 1, 500), instance("1.3", 1, 10));

        var parts = new PartPlanner(100).plan(all);

        assertThat(parts).extracting(List::size).containsExactly(1, 1, 1);
        assertThat(parts.get(1).getFirst().size()).isEqualTo(500);
    }

    @Test
    void keepsArrivalOrderOfSeries() {
        List<ReceivedInstance> all = List.of(instance("1.9", 1, 10), instance("1.1", 1, 10), instance("1.9", 2, 10));

        var parts = new PartPlanner(1000).plan(all);

        assertThat(parts.getFirst()).extracting(ReceivedInstance::sopInstanceUid)
                .containsExactly("1.9.1", "1.9.2", "1.1.1");
    }

    @Test
    void emptyInputGivesNoPart() {
        assertThat(new PartPlanner(100).plan(List.of())).isEmpty();
    }
}
