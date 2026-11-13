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
        EnrollmentRequest r = EnrollmentRequest.verify(request);
        BoxEntry entry = new BoxEntry(Suite.ID, id, r.boxId(), r.displayName(), r.signingKeys(), r.encryptionKeys(),
                r.transport(),
                serial, now, now.plus(validity), false, null, null, groups);
        return SignedDocument.sign(Json.write(entry), keys);
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

    /** Identifiant d'organisation : lettres minuscules, chiffres et tirets, 64 caractères au plus. */
    static String requireValidId(String id) {
        if (id == null || !id.matches("[a-z0-9][a-z0-9-]{0,63}")) {
            throw new IllegalArgumentException(
                    "Identifiant d'organisation invalide (minuscules, chiffres et tirets, 64 caractères au plus)");
        }
        return id;
    }
}
