package com.imagunity.directory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Objects;

/**
 * État d'un appareil DICOM du site d'une box, tel que la box le constate : dernier essai de connexion (C-ECHO) d'une
 * destination, dernier envoi reçu d'une source, dernière connexion refusée faute de venir de l'adresse déclarée.
 * Porté par le compte rendu signé de la box ; ne contient aucune donnée de patient.
 *
 * @param node        nom de l'appareil dans la configuration
 * @param echoAt      date du dernier C-ECHO, ou {@code null} s'il n'a pas été essayé (pas une destination)
 * @param echoError   raison de l'échec de ce C-ECHO, ou {@code null} s'il a réussi
 * @param receivedAt  date de la dernière association acceptée de cette source, ou {@code null}
 * @param refusedAt   date de la dernière association refusée (adresse inattendue), ou {@code null}
 * @param refusedFrom adresse d'où venait cette association refusée
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeCheck(String node, Instant echoAt, String echoError, Instant receivedAt, Instant refusedAt,
                        String refusedFrom) {

    public NodeCheck {
        Objects.requireNonNull(node, "node");
        echoError = cut(echoError);
        refusedFrom = cut(refusedFrom);
    }

    /** L'essai de connexion a réussi. */
    public boolean echoOk() {
        return echoAt != null && echoError == null;
    }

    /** Résumé qui ne change qu'avec l'état (pas avec les dates) : un changement déclenche un compte rendu. */
    public String state() {
        return node + ":" + (echoAt == null ? "-" : echoError == null ? "ok" : "ko") + ":"
                + (receivedAt == null ? "-" : "r") + ":" + (refusedAt == null ? "-" : "x");
    }

    private static String cut(String text) {
        return text == null || text.length() <= 200 ? text : text.substring(0, 200);
    }
}
