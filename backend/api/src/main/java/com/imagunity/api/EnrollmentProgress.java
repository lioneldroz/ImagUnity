package com.imagunity.api;

import java.time.Instant;

/**
 * Mise en service de la box, pour la console : organisation dont elle a épinglé la clé, annuaire, et où en est sa
 * demande d'enrôlement (envoyée automatiquement tant que la box n'est pas dans l'annuaire).
 */
public final class EnrollmentProgress {

    /**
     * @param organizationShortCode empreinte de la clé de l'organisation, vérifiée au démarrage
     * @param codeProvided          un code d'enrôlement est configuré
     * @param submittedAt           dernière demande acceptée par l'annuaire, ou {@code null}
     * @param attemptAt             dernier essai d'envoi, ou {@code null}
     * @param error                 raison du dernier échec d'envoi, ou {@code null}
     */
    public record View(String organization, String organizationShortCode, String directoryUrl, boolean codeProvided,
                       Instant submittedAt, Instant attemptAt, String error) {
    }

    private final String organization;
    private final String organizationShortCode;
    private final String directoryUrl;
    private final boolean codeProvided;
    private volatile Instant submittedAt;
    private volatile Instant attemptAt;
    private volatile String error;

    public EnrollmentProgress(String organization, String organizationShortCode, String directoryUrl,
                              boolean codeProvided) {
        this.organization = organization;
        this.organizationShortCode = organizationShortCode;
        this.directoryUrl = directoryUrl;
        this.codeProvided = codeProvided;
    }

    /** La demande a été acceptée par l'annuaire. */
    public void submitted(Instant at) {
        submittedAt = at;
        attemptAt = at;
        error = null;
    }

    /** La demande n'a pas pu être déposée. */
    public void failed(Instant at, String reason) {
        attemptAt = at;
        error = reason;
    }

    public View view() {
        return new View(organization, organizationShortCode, directoryUrl, codeProvided, submittedAt, attemptAt, error);
    }
}
