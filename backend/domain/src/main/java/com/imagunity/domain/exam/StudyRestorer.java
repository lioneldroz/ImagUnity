package com.imagunity.domain.exam;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Restitution d'une étude reçue au PACS local (port ; implémenté par le module dicom).
 *
 * <p>L'implémentation doit valider chaque instance avant tout envoi : les fichiers viennent d'un
 * partenaire et ne doivent pas pouvoir exploiter une faille du PACS local.
 */
public interface StudyRestorer {

    /**
     * Instance à restituer, avec les identifiants attendus d'après le manifeste du paquet.
     *
     * @param seriesInstanceUid série attendue
     * @param sopInstanceUid    instance attendue
     * @param file              fichier DICOM (Part 10) extrait du paquet
     */
    record Instance(String seriesInstanceUid, String sopInstanceUid, Path file) {
    }

    /**
     * Valide puis transmet toutes les instances de l'étude au PACS local.
     *
     * @throws IOException si une instance est invalide ou si le PACS refuse ou est injoignable ;
     *                     aucune instance n'est envoyée si la validation échoue
     */
    void restore(String studyInstanceUid, List<Instance> instances) throws IOException;
}
