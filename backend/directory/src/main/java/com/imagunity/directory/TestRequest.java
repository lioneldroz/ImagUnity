package com.imagunity.directory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Demande d'essai de connexion, signée par le responsable de l'organisation : la box visée essaie tout de suite la
 * connexion (C-ECHO) de chacune de ses destinations DICOM et rend compte. Déposée dans l'annuaire, qui réveille la box
 * par son flux d'événements ; la box ne la croit qu'après en avoir vérifié la signature. Ne contient aucune donnée de
 * patient, et ne déclenche que des essais de connexion : la rejouer est sans effet au-delà de {@link #MAX_AGE}.
 *
 * @param type         {@link #TYPE}
 * @param organization organisation du responsable
 * @param boxId        box visée
 * @param id           identifiant de la demande, repris dans le compte rendu qui y répond
 * @param requestedAt  date de la demande
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record TestRequest(String type, String organization, UUID boxId, String id, Instant requestedAt) {

    public static final String TYPE = "imagunity.test.request";
    /** Au-delà, une demande n'est plus suivie : elle ne sert qu'à un essai immédiat. */
    public static final Duration MAX_AGE = Duration.ofMinutes(15);
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    public TestRequest {
        Objects.requireNonNull(organization, "organization");
        Objects.requireNonNull(boxId, "boxId");
        Objects.requireNonNull(requestedAt, "requestedAt");
        if (id == null || !id.matches("[0-9a-f-]{36}")) {
            throw new IllegalArgumentException("Identifiant de demande d'essai invalide");
        }
    }

    /** Nouvelle demande pour cette box. */
    public static TestRequest create(String organization, UUID boxId, Instant now) {
        return new TestRequest(TYPE, organization, boxId, UUID.randomUUID().toString(), now);
    }

    SignedDocument sign(HybridKeyPair organizationKeys) {
        return SignedDocument.sign(Json.write(this), organizationKeys);
    }

    /**
     * Vérifie une demande : signature du responsable, type, organisation, et date (ni future, ni plus ancienne que
     * {@link #MAX_AGE}).
     *
     * @throws DirectoryException si la demande n'est pas recevable
     */
    public static TestRequest verify(SignedDocument document, String organization, HybridPublicKey organizationKey,
                                     Instant now) {
        if (!document.isSignedBy(organizationKey)) {
            throw new DirectoryException("Demande d'essai non signée par le responsable de l'organisation");
        }
        TestRequest request = Json.read(document.payload(), TestRequest.class, "Demande d'essai");
        if (!TYPE.equals(request.type())) {
            throw new DirectoryException("Document de type « " + request.type() + " », demande d'essai attendue");
        }
        if (!organization.equals(request.organization())) {
            throw new DirectoryException("Demande d'essai d'une autre organisation : " + request.organization());
        }
        if (request.requestedAt().isAfter(now.plus(CLOCK_SKEW))) {
            throw new DirectoryException("Demande d'essai datée du futur (" + request.requestedAt() + ")");
        }
        if (request.requestedAt().isBefore(now.minus(MAX_AGE))) {
            throw new DirectoryException("Demande d'essai trop ancienne (" + request.requestedAt() + ")");
        }
        return request;
    }
}
