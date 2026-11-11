package com.imagunity.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Message d'annonce envoyé au destinataire une fois tous les paquets déposés sur les relais.
 *
 * <p>Il permet au destinataire de rattacher les fichiers reçus à un transfert, de vérifier
 * l'empreinte de chaque paquet de bout en bout, et de savoir quand il a tout reçu.
 * Il voyage chiffré de bout en bout par le transport ; il contient des identifiants DICOM
 * mais aucune donnée démographique du patient.
 *
 * @param transferId       transfert (identifiant de l'émetteur, repris dans l'accusé de réception)
 * @param packageGroupId   identifiant commun aux paquets, présent dans leurs manifestes et leurs noms
 * @param studyInstanceUid étude transférée
 * @param parts            paquets du transfert
 * @param announcedAt      émission de l'annonce : une relance porte une date plus récente, une relecture
 *                         (rattrapage après redémarrage) la même date ; {@code null} si absente
 */
public record TransferAnnouncement(UUID transferId, String packageGroupId, String studyInstanceUid,
                                   List<Part> parts, Instant announcedAt) {

    public TransferAnnouncement {
        parts = parts == null ? List.of() : List.copyOf(parts);
    }

    /** Annonce sans date d'émission. */
    public TransferAnnouncement(UUID transferId, String packageGroupId, String studyInstanceUid, List<Part> parts) {
        this(transferId, packageGroupId, studyInstanceUid, parts, null);
    }

    /**
     * @param index    rang du paquet, à partir de 1
     * @param count    nombre de paquets
     * @param fileName nom du fichier tel qu'il arrive chez le destinataire
     * @param size     taille du fichier, en octets
     * @param sha256   empreinte SHA-256 du fichier
     */
    public record Part(int index, int count, String fileName, long size, String sha256) {
    }
}
