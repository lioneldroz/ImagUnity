package com.imagunity.dicom;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Regroupe les instances reçues par étude, par destination DICOM et par partenaire, et signale une étude
 * comme complète quand plus rien n'arrive pendant le délai d'inactivité.
 *
 * <p>Une même instance reçue deux fois (même SOP Instance UID) n'est comptée qu'une fois,
 * la dernière version reçue l'emportant.
 */
final class StudyAssembler {

    private static final System.Logger LOG = System.getLogger(StudyAssembler.class.getName());

    private record Key(String calledAet, String destination, String studyInstanceUid) {
    }

    private static final class Pending {
        final String destination;
        final Set<String> callingAets = new LinkedHashSet<>();
        final Map<String, ReceivedInstance> instances = new LinkedHashMap<>();
        final Instant first;
        Instant last;

        Pending(String destination, Instant now) {
            this.destination = destination;
            this.first = now;
            this.last = now;
        }
    }

    private final Clock clock;
    private final Duration idleTimeout;
    private final StudyListener listener;
    private final Map<Key, Pending> pending = new HashMap<>();

    StudyAssembler(Clock clock, Duration idleTimeout, StudyListener listener) {
        this.clock = clock;
        this.idleTimeout = idleTimeout;
        this.listener = listener;
    }

    /**
     * Ajoute une instance à son étude.
     *
     * @param callingAet émetteur local, ou {@code null} pour une instance reprise après redémarrage
     */
    synchronized void add(String calledAet, String destination, String callingAet,
                          String studyInstanceUid, ReceivedInstance instance) {
        Instant now = clock.instant();
        Pending study = pending.computeIfAbsent(new Key(calledAet, destination, studyInstanceUid),
                k -> new Pending(destination, now));
        study.instances.put(instance.sopInstanceUid(), instance);
        if (callingAet != null) {
            study.callingAets.add(callingAet);
        }
        study.last = now;
    }

    /** Signale les études inactives depuis plus que le délai ; à appeler périodiquement. */
    void flushIdle() {
        List<ReceivedStudy> completed = new ArrayList<>();
        synchronized (this) {
            Instant limit = clock.instant().minus(idleTimeout);
            for (Iterator<Map.Entry<Key, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Key, Pending> entry = it.next();
                Pending study = entry.getValue();
                if (!study.last.isAfter(limit)) {
                    it.remove();
                    completed.add(new ReceivedStudy(entry.getKey().studyInstanceUid(), entry.getKey().calledAet(),
                            study.destination, study.callingAets, List.copyOf(study.instances.values()),
                            study.first, study.last));
                }
            }
        }
        // Hors verrou : le consommateur peut prendre du temps sans bloquer la réception.
        for (ReceivedStudy study : completed) {
            LOG.log(Level.INFO, "Étude complète pour {0} via {1} : {2} instance(s), {3} série(s), {4} octets",
                    study.destination(), study.calledAet(), study.instances().size(), study.seriesCount(),
                    study.totalSize());
            try {
                listener.studyReceived(study);
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Le traitement d'une étude reçue a échoué", e);
            }
        }
    }

    synchronized int pendingStudies() {
        return pending.size();
    }
}
