package com.imagunity.domain.exam;

import java.util.List;

/**
 * Priorité d'envoi d'une série (port ; règles par champ DICOM dans le module dicom) : de 1, la plus haute, à 9. Les
 * séries prioritaires sont empaquetées et envoyées les premières, et restituées au fil de l'eau chez le destinataire.
 */
@FunctionalInterface
public interface SeriesPriority {

    /** Priorité des séries qu'aucune règle ne couvre, par défaut. */
    int DEFAULT = 5;
    int HIGHEST = 1;
    int LOWEST = 9;

    /** Sans règle : toutes les séries à la priorité par défaut, dans l'ordre d'arrivée. */
    SeriesPriority NONE = series -> DEFAULT;

    /**
     * Priorité d'une série.
     *
     * @param series instances de la série, dans l'ordre d'arrivée
     */
    int of(List<ReceivedInstance> series);
}
