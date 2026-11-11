package com.imagunity.dicom;

import com.imagunity.domain.exam.ReceivedStudy;

/** Reçoit les études complètes, prêtes à être empaquetées. */
@FunctionalInterface
public interface StudyListener {

    /**
     * Appelé une fois par étude complète. Les fichiers restent dans le dossier de réception :
     * c'est au consommateur de les déplacer ou de les supprimer une fois pris en charge.
     *
     * <p>Livraison « au moins une fois » : après un redémarrage, une étude encore présente
     * dans le dossier de réception est signalée à nouveau.
     */
    void studyReceived(ReceivedStudy study);
}
