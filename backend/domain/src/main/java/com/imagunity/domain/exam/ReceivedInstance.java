package com.imagunity.domain.exam;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Instance DICOM reçue du PACS local et écrite sur le disque de la box.
 *
 * @param sopClassUid       classe SOP (type d'objet : image CT, IRM…)
 * @param sopInstanceUid    identifiant unique de l'instance, conservé tel quel jusqu'au destinataire
 * @param seriesInstanceUid série à laquelle l'instance appartient
 * @param transferSyntaxUid syntaxe de transfert dans laquelle l'instance a été reçue
 * @param file              fichier DICOM (Part 10) sur le disque de la box
 * @param size              taille du fichier en octets
 */
public record ReceivedInstance(
        String sopClassUid,
        String sopInstanceUid,
        String seriesInstanceUid,
        String transferSyntaxUid,
        Path file,
        long size) {

    public ReceivedInstance {
        Objects.requireNonNull(sopInstanceUid, "sopInstanceUid");
        Objects.requireNonNull(seriesInstanceUid, "seriesInstanceUid");
        Objects.requireNonNull(file, "file");
    }
}
