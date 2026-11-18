package com.imagunity.directory;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Le responsable de l'organisation : signe les entrées de l'annuaire (enrôlement, renouvellement, révocation).
 *
 * <p>Sa clé privée reste hors ligne ; seule sa clé publique, épinglée par les box, circule.
 */
public final class Organization {

    private final String id;
    private final HybridKeyPair keys;

    public Organization(String id, HybridKeyPair keys) {
        this.id = requireValidId(id);
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    public String id() {
        return id;
    }

    public HybridPublicKey publicKey() {
        return keys.publicKey();
    }

    /**
     * Enrôle la box qui a signé cette demande.
     *
     * @param validity durée de validité de l'entrée
     * @param serial   numéro d'émission (1 au premier enrôlement, puis croissant)
     */
    public SignedDocument enroll(SignedDocument request, Instant now, Duration validity, long serial) {
        return enroll(request, now, validity, serial, java.util.List.of());
    }

    /**
     * Enrôle la box qui a signé cette demande, dans des groupes (configuration centralisée). Pour changer les groupes
     * d'une box, réémettre son entrée avec un numéro supérieur.
     *
     * @param groups groupes de la box, fixés par le responsable (la demande de la box n'en contient pas)
     */
    public SignedDocument enroll(SignedDocument request, Instant now, Duration validity, long serial,
                                 java.util.List<String> groups) {
        return enroll(request, now, validity, serial, groups, null);
    }

    /**
     * Enrôle la box qui a signé cette demande, sous le nom choisi par le responsable (code d'enrôlement).
     *
     * @param displayName nom de la box dans l'annuaire ({@code null} : celui de la demande)
     */
    public SignedDocument enroll(SignedDocument request, Instant now, Duration validity, long serial,
                                 java.util.List<String> groups, String displayName) {
        EnrollmentRequest r = EnrollmentRequest.verify(request);
        BoxEntry entry = new BoxEntry(Suite.ID, id, r.boxId(), displayName == null ? r.displayName() : displayName,
                r.signingKeys(), r.encryptionKeys(), r.transport(),
                serial, now, now.plus(validity), false, null, null, groups, r.protocols());
        return SignedDocument.sign(Json.write(entry), keys);
    }

    /**
     * Renouvelle l'entrée d'une box : mêmes clés, adresses et groupes, nouvelle période de validité à partir de
     * maintenant, numéro suivant.
     *
     * @throws DirectoryException si l'entrée n'est pas signée par cette organisation, ou si la box est révoquée
     */
    public SignedDocument renew(SignedDocument entry, Instant now, Duration validity) {
        return renew(entry, now, validity, null);
    }

    /**
     * Renouvelle l'entrée d'une box en mettant à jour les versions du protocole qu'elle annonce (celles de son dernier
     * compte rendu, signé par elle) : une box mise à jour annonce ainsi ses nouvelles versions.
     *
     * @param protocols versions à annoncer ; {@code null} : celles de l'entrée
     */
    public SignedDocument renew(SignedDocument entry, Instant now, Duration validity, java.util.List<String> protocols) {
        if (!entry.isSignedBy(publicKey())) {
            throw new DirectoryException("Entrée non signée par cette organisation");
        }
        BoxEntry current = Json.read(entry.payload(), BoxEntry.class, "Entrée de box");
        if (current.revoked()) {
            throw new DirectoryException("Box révoquée : pas de renouvellement");
        }
        return SignedDocument.sign(Json.write(protocols == null ? current.renew(now, validity)
                : current.renew(now, validity, protocols)), keys);
    }

    /**
     * Révoque la box de cette entrée (qu'elle soit encore valide ou non) : entrée de numéro suivant.
     *
     * @throws DirectoryException si l'entrée n'est pas signée par cette organisation
     */
    public SignedDocument revoke(SignedDocument entry, Instant now, String reason) {
        if (!entry.isSignedBy(publicKey())) {
            throw new DirectoryException("Entrée non signée par cette organisation");
        }
        BoxEntry current = Json.read(entry.payload(), BoxEntry.class, "Entrée de box");
        if (current.revoked()) {
            throw new DirectoryException("Box déjà révoquée");
        }
        return SignedDocument.sign(Json.write(current.revoke(now, reason)), keys);
    }

    /**
     * Reconnaît une organisation partenaire (fédération) : première reconnaissance, n° 1.
     *
     * @throws IllegalArgumentException si le partenaire est cette organisation, ou si un champ est invalide
     */
    public SignedDocument recognize(Recognition.Partner partner, Recognition.Scope scope, Instant now,
                                    Duration validity) {
        requireOther(partner);
        return SignedDocument.sign(Json.write(Recognition.first(id, partner, scope, now, validity)), keys);
    }

    /**
     * Réémet la reconnaissance d'un partenaire, avec un nouveau périmètre, de nouveaux relais ou une nouvelle
     * validité : numéro suivant. Après une révocation, rétablit l'accord.
     *
     * @throws DirectoryException si le document n'est pas une reconnaissance de cette organisation
     */
    public SignedDocument renewRecognition(SignedDocument current, Recognition.Partner partner,
                                           Recognition.Scope scope, Instant now, Duration validity) {
        Recognition r = ownRecognition(current, true);
        requireOther(partner);
        return SignedDocument.sign(Json.write(r.next(partner, scope, now, validity)), keys);
    }

    /**
     * Révoque la reconnaissance d'un partenaire : numéro suivant, l'accord cesse aussitôt.
     *
     * @throws DirectoryException si le document n'est pas une reconnaissance de cette organisation, ou est déjà révoqué
     */
    public SignedDocument revokeRecognition(SignedDocument current, Instant now, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Motif de révocation obligatoire");
        }
        return SignedDocument.sign(Json.write(ownRecognition(current, false).revoke(now, reason.strip())), keys);
    }

    private Recognition ownRecognition(SignedDocument document, boolean revokedAllowed) {
        if (!document.isSignedBy(publicKey())) {
            throw new DirectoryException("Reconnaissance non signée par cette organisation");
        }
        Recognition r = Json.read(document.payload(), Recognition.class, "Reconnaissance");
        if (r.revoked() && !revokedAllowed) {
            throw new DirectoryException("Reconnaissance déjà révoquée");
        }
        return r;
    }

    private void requireOther(Recognition.Partner partner) {
        if (partner.fingerprint().equals(publicKey().fingerprint())) {
            throw new IllegalArgumentException("Une organisation ne se reconnaît pas elle-même");
        }
    }

    /**
     * Délègue la signature des preuves de fraîcheur à une clé en ligne, pour une durée courte.
     *
     * @param serial numéro de la délégation (le suivant à chaque renouvellement)
     */
    public SignedDocument delegateFreshness(HybridPublicKey freshnessKey, Instant now, Duration validity, long serial) {
        if (freshnessKey.fingerprint().equals(publicKey().fingerprint())) {
            throw new IllegalArgumentException("La clé de fraîcheur doit être distincte de la clé du responsable");
        }
        return SignedDocument.sign(Json.write(new FreshnessDelegation(Suite.ID, FreshnessDelegation.TYPE, id,
                freshnessKey.encoded(), serial, now, now.plus(validity))), keys);
    }

    /**
     * Signe un document de configuration centralisée de cette organisation.
     *
     * @throws DirectoryException si le document vise une autre organisation ou est mal formé
     */
    public SignedDocument signConfiguration(CentralConfiguration configuration) {
        if (!id.equals(configuration.organization())) {
            throw new DirectoryException("Configuration de l'organisation « " + configuration.organization()
                    + " », responsable de « " + id + " »");
        }
        return configuration.sign(keys);
    }

    /** Demande d'essai de connexion des appareils DICOM de cette box, signée. */
    public SignedDocument signTestRequest(java.util.UUID boxId, java.time.Instant now) {
        return TestRequest.create(id, boxId, now).sign(keys);
    }

    /** Identifiant d'organisation : lettres minuscules, chiffres et tirets, 64 caractères au plus. */
    static String requireValidId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(
                    "Identifiant d'organisation invalide (minuscules, chiffres et tirets, 64 caractères au plus)");
        }
        return id;
    }
}
