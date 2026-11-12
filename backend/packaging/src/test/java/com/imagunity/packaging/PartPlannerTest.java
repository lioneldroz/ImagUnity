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

    /** Priorité selon le préfixe de la série : « p1… » → 1, « p9… » → 9, sinon la priorité par défaut. */
    private static int priority(List<ReceivedInstance> series) {
        String uid = series.getFirst().seriesInstanceUid();
        return uid.startsWith("p") ? uid.charAt(1) - '0' : com.imagunity.domain.exam.SeriesPriority.DEFAULT;
    }

    @Test
    void prioritySeriesComeFirstInTheirOwnParts() {
        List<ReceivedInstance> all = new ArrayList<>();
        all.addAll(series("1.1", 3, 10));     // défaut (5), arrivée en premier
        all.addAll(series("p9.1", 2, 10));    // localisateurs, en dernier
        all.addAll(series("p1.1", 2, 10));    // prioritaire
        all.addAll(series("1.2", 2, 10));     // défaut
        all.addAll(series("p1.2", 1, 10));    // prioritaire, arrivée plus tard

        PartPlanner.Plan plan = new PartPlanner(1000, PartPlannerTest::priority).planWithPriorities(all);

        assertThat(plan.parts()).extracting(PartPlannerTest::seriesOf).containsExactly(
                List.of("p1.1", "p1.2"), List.of("1.1", "1.2"), List.of("p9.1"));
        assertThat(plan.priorities()).containsExactly(1, 5, 9);
    }

    @Test
    void largePrioritySeriesAreStillSplitAndPrioritiesAreBounded() {
        List<ReceivedInstance> all = new ArrayList<>();
        all.addAll(series("1.1", 2, 40));
        all.addAll(series("p1.1", 5, 40));    // 200 octets : coupée en paquets de 100
        PartPlanner.Plan plan = new PartPlanner(100, PartPlannerTest::priority).planWithPriorities(all);
        assertThat(plan.priorities()).containsExactly(1, 1, 1, 5);
        assertThat(plan.parts().getLast()).extracting(ReceivedInstance::seriesInstanceUid).containsOnly("1.1");

        PartPlanner bounded = new PartPlanner(1000, s -> s.getFirst().seriesInstanceUid().equals("1.1") ? -3 : 42);
        assertThat(bounded.planWithPriorities(all).priorities()).as("ramenées entre 1 et 9").containsExactly(1, 9);
    }
}

