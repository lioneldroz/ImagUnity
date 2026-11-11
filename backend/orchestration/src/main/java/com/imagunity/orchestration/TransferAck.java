package com.imagunity.orchestration;

import java.util.UUID;

/**
 * Accusé de bout en bout, envoyé par le destinataire à l'émetteur une fois le transfert terminé chez lui.
 *
 * <p>Il voyage dans la même conversation chiffrée et authentifiée de bout en bout que l'annonce : seul le
 * partenaire appairé peut l'émettre. L'émetteur vérifie en plus qu'il vient bien du destinataire du transfert
 * et que l'empreinte des paquets ({@link TransferMessages#partsDigest}) est celle de ce qu'il a envoyé.
 *
 * @param transferId     transfert, tel qu'annoncé par l'émetteur
 * @param packageGroupId groupe des paquets annoncés
 * @param result         {@link Result#RESTORED} si l'étude est arrivée complète, identique et restituée au PACS
 * @param instances      nombre d'instances vérifiées et restituées (0 en cas d'échec)
 * @param partsDigest    empreinte des paquets annoncés, calculée par le destinataire
 * @param reason         cause de l'échec ({@code null} si restitué) ; texte technique, sans donnée patient
 */
public record TransferAck(UUID transferId, String packageGroupId, Result result, int instances, String partsDigest,
                          String reason) {

    /** Longueur maximale de la cause transmise. */
    public static final int MAX_REASON = 500;

    public enum Result {
        RESTORED,
        FAILED
    }
}
