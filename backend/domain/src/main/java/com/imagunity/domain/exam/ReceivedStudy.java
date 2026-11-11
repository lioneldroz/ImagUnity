package com.imagunity.domain.exam;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Étude complète reçue du PACS local, prête à être empaquetée pour un destinataire.
 *
 * <p>Une étude est considérée complète quand plus aucune instance n'arrive pendant
 * le délai d'inactivité configuré. La même étude envoyée vers deux destinations DICOM
 * différentes donne deux {@code ReceivedStudy} distinctes.
 *
 * @param studyInstanceUid identifiant de l'étude, conservé tel quel
 * @param calledAet        destination DICOM (AET appelé) sur laquelle l'étude a été envoyée
 * @param destination      partenaire désigné par cette destination (configuration de routage)
 * @param callingAets      AET des émetteurs locaux (vide si l'étude a été reprise après un redémarrage)
 * @param instances        instances reçues, sans doublon de SOP Instance UID
 * @param firstReceivedAt  arrivée de la première instance
 * @param lastReceivedAt   arrivée de la dernière instance
 */
public record ReceivedStudy(
        String studyInstanceUid,
        String calledAet,
        String destination,
        Set<String> callingAets,
        List<ReceivedInstance> instances,
        Instant firstReceivedAt,
        Instant lastReceivedAt) {

    public ReceivedStudy {
        Objects.requireNonNull(studyInstanceUid, "studyInstanceUid");
        Objects.requireNonNull(calledAet, "calledAet");
        Objects.requireNonNull(destination, "destination");
        callingAets = Set.copyOf(callingAets);
        instances = List.copyOf(instances);
    }

    /** Nombre de séries distinctes. */
    public long seriesCount() {
        return instances.stream().map(ReceivedInstance::seriesInstanceUid).distinct().count();
    }

    /** Taille totale des instances, en octets. */
    public long totalSize() {
        return instances.stream().mapToLong(ReceivedInstance::size).sum();
    }
}
