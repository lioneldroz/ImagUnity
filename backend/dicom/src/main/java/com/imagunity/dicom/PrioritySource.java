package com.imagunity.dicom;

import java.time.Instant;

/** Règles de priorité en vigueur, pour la console (lecture seule). */
public interface PrioritySource {

    /**
     * État des règles.
     *
     * @param rules    règles en vigueur
     * @param source   « fichier &lt;chemin&gt; », « configuration » ou « aucune règle »
     * @param loadedAt chargement des règles en vigueur
     * @param error    dernier refus d'un fichier invalide, ou {@code null}
     */
    record Snapshot(PriorityRules rules, String source, Instant loadedAt, String error) {
    }

    Snapshot snapshot();
}
