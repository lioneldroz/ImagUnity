package com.imagunity.packaging;

import com.imagunity.domain.exam.ReceivedInstance;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Répartit les instances d'une étude en paquets d'au plus {@code maxPartSize} octets.
 *
 * <p>Règles, par ordre de priorité :
 * <ol>
 *   <li>une série qui tient dans un paquet n'est jamais coupée ;</li>
 *   <li>les séries sont prises dans l'ordre d'arrivée et regroupées tant que le paquet n'est pas plein ;</li>
 *   <li>une série plus grande qu'un paquet est coupée entre deux instances ;</li>
 *   <li>une instance plus grande qu'un paquet forme à elle seule un paquet.</li>
 * </ol>
 */
final class PartPlanner {

    private final long maxPartSize;

    PartPlanner(long maxPartSize) {
        this.maxPartSize = maxPartSize;
    }

    List<List<ReceivedInstance>> plan(List<ReceivedInstance> instances) {
        Map<String, List<ReceivedInstance>> bySeries = new LinkedHashMap<>();
        for (ReceivedInstance instance : instances) {
            bySeries.computeIfAbsent(instance.seriesInstanceUid(), k -> new ArrayList<>()).add(instance);
        }

        List<List<ReceivedInstance>> parts = new ArrayList<>();
        List<ReceivedInstance> current = new ArrayList<>();
        long currentSize = 0;
        for (List<ReceivedInstance> series : bySeries.values()) {
            long seriesSize = series.stream().mapToLong(ReceivedInstance::size).sum();
            if (seriesSize <= maxPartSize) {
                if (currentSize + seriesSize > maxPartSize && !current.isEmpty()) {
                    parts.add(current);
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
                    current = new ArrayList<>();
                    currentSize = 0;
                }
                current.add(instance);
                currentSize += instance.size();
            }
        }
        if (!current.isEmpty()) {
            parts.add(current);
        }
        return parts;
    }
}
