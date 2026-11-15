package com.imagunity.directory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Preuve de fraîcheur telle que servie par un annuaire : la délégation de la clé de fraîcheur (signée par le
 * responsable) et la preuve elle-même (signée par la clé déléguée).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Freshness(SignedDocument delegation, SignedDocument proof) {

    /** Avance d'horloge tolérée sur la date d'une preuve. */
    static final Duration SKEW = Duration.ofMinutes(5);

    public Freshness {
        Objects.requireNonNull(delegation, "delegation");
        Objects.requireNonNull(proof, "proof");
    }

    public String toJson() {
        return Json.write(this);
    }

    public static Freshness fromJson(String json) {
        return Json.read(json, Freshness.class, "Preuve de fraîcheur");
    }

    /**
     * Vérifie la délégation (clé du responsable, organisation, validité à la date de la preuve) puis la preuve (clé
     * déléguée, organisation, date pas dans le futur).
     *
     * @throws DirectoryException si l'une ou l'autre ne se vérifie pas
     */
    public FreshnessProof verify(String organization, HybridPublicKey organizationKey, Instant now) {
        if (!delegation.isSignedBy(organizationKey)) {
            throw new DirectoryException("Délégation de fraîcheur non signée par le responsable de l'organisation");
        }
        FreshnessDelegation d = Json.read(delegation.payload(), FreshnessDelegation.class, "Délégation de fraîcheur");
        Suite.require(d.suite());
        if (!organization.equals(d.organization())) {
            throw new DirectoryException("Délégation de fraîcheur d'une autre organisation : " + d.organization());
        }
        if (!proof.isSignedBy(d.publicKey())) {
            throw new DirectoryException("Preuve de fraîcheur non signée par la clé déléguée");
        }
        FreshnessProof p = Json.read(proof.payload(), FreshnessProof.class, "Preuve de fraîcheur");
        Suite.require(p.suite());
        if (!organization.equals(p.organization())) {
            throw new DirectoryException("Preuve de fraîcheur d'une autre organisation : " + p.organization());
        }
        if (p.issuedAt().isAfter(now.plus(SKEW))) {
            throw new DirectoryException("Preuve de fraîcheur datée du futur : " + p.issuedAt());
        }
        if (!d.coversAt(p.issuedAt())) {
            throw new DirectoryException("Preuve de fraîcheur signée hors de la validité de la délégation (jusqu'au "
                    + d.validUntil() + ")");
        }
        return p;
    }

    /** Délégation, lue sans vérification (affichage). */
    public FreshnessDelegation delegationContent() {
        return Json.read(delegation.payload(), FreshnessDelegation.class, "Délégation de fraîcheur");
    }
}
