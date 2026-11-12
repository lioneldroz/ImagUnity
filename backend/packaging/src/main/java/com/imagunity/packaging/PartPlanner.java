package com.imagunity.packaging;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.SeriesPriority;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Répartit les instances d'une étude en paquets d'au plus {@code maxPartSize} octets.
 *
 * <p>Règles, par ordre de priorité :
 * <ol>
 *   <li>les séries sont rangées par priorité ({@link SeriesPriority}, 1 d'abord), puis dans l'ordre d'arrivée ; un
 *       changement de priorité ouvre un nouveau paquet, pour que les premiers paquets ne contiennent que les séries
 *       prioritaires (restituées au fil de l'eau chez le destinataire) ;</li>
 *   <li>une série qui tient dans un paquet n'est jamais coupée ;</li>
 *   <li>les séries de même priorité sont regroupées tant que le paquet n'est pas plein ;</li>
 *   <li>une série plus grande qu'un paquet est coupée entre deux instances ;</li>
 *   <li>une instance plus grande qu'un paquet forme à elle seule un paquet.</li>
 * </ol>
 */
final class PartPlanner {

    /** Paquets prévus, avec la priorité de chacun. */
    record Plan(List<List<ReceivedInstance>> parts, List<Integer> priorities) {
    }

    private final long maxPartSize;
    private final SeriesPriority priority;

    PartPlanner(long maxPartSize) {
        this(maxPartSize, SeriesPriority.NONE);
    }

    PartPlanner(long maxPartSize, SeriesPriority priority) {
        this.maxPartSize = maxPartSize;
        this.priority = priority;
    }

    List<List<ReceivedInstance>> plan(List<ReceivedInstance> instances) {
        return planWithPriorities(instances).parts();
    }

    Plan planWithPriorities(List<ReceivedInstance> instances) {
        Map<String, List<ReceivedInstance>> bySeries = new LinkedHashMap<>();
        for (ReceivedInstance instance : instances) {
            bySeries.computeIfAbsent(instance.seriesInstanceUid(), k -> new ArrayList<>()).add(instance);
        }
        record Ranked(int priority, List<ReceivedInstance> series) {
        }
        List<Ranked> ranked = new ArrayList<>();
        for (List<ReceivedInstance> series : bySeries.values()) {
            int p = Math.clamp(priority.of(series), SeriesPriority.HIGHEST, SeriesPriority.LOWEST);
            ranked.add(new Ranked(p, series));
        }
        ranked.sort(java.util.Comparator.comparingInt(Ranked::priority)); // tri stable : ordre d'arrivée conservé

        List<List<ReceivedInstance>> parts = new ArrayList<>();
        List<Integer> priorities = new ArrayList<>();
        List<ReceivedInstance> current = new ArrayList<>();
        long currentSize = 0;
        int currentPriority = -1;
        for (Ranked r : ranked) {
            List<ReceivedInstance> series = r.series();
            if (r.priority() != currentPriority && !current.isEmpty()) {
                parts.add(current);
                priorities.add(currentPriority);
                current = new ArrayList<>();
                currentSize = 0;
            }
            currentPriority = r.priority();
            long seriesSize = series.stream().mapToLong(ReceivedInstance::size).sum();
            if (seriesSize <= maxPartSize) {
                if (currentSize + seriesSize > maxPartSize && !current.isEmpty()) {
                    parts.add(current);
                    priorities.add(currentPriority);
                    current = new ArrayList<>();
                    currentSize = 0;
                }
                current.addAll(series);
                currentSize += seriesSize;
                continue;
            }
            for (ReceivedInstance instance : series) {
                if (currentSize + instance.size() > maxPartSize && !current.isEmpty()) {
                    parts.add(current);
                    priorities.add(currentPriority);
                    current = new ArrayList<>();
                    currentSize = 0;
                }
                current.add(instance);
                currentSize += instance.size();
            }
        }
        if (!current.isEmpty()) {
            parts.add(current);
            priorities.add(currentPriority);
        }
        return new Plan(parts, priorities);
    }
}
