package com.imagunity.directory;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

/**
 * Vérification des reconnaissances de partenaires (fédération) : signées par la clé épinglée du responsable, de son
 * organisation, sans retour en arrière pour un même partenaire. Côté box, relais et service d'annuaire.
 */
public final class RecognitionVerifier {

    private final String organization;
    private final HybridPublicKey organizationKey;
    private final SerialMemory serials;

    public RecognitionVerifier(String organization, HybridPublicKey organizationKey, SerialMemory serials) {
        this.organization = organization;
        this.organizationKey = organizationKey;
        this.serials = serials;
    }

    /**
     * Vérifie une reconnaissance et retient son numéro.
     *
     * @throws DirectoryException si elle est mal signée, d'une autre organisation, illisible, ou un retour en arrière
     */
    public Recognition verify(SignedDocument document) {
        if (!document.isSignedBy(organizationKey)) {
            throw new DirectoryException("Reconnaissance non signée par le responsable de l'organisation");
        }
        Recognition recognition = Json.read(document.payload(), Recognition.class, "Reconnaissance");
        Suite.require(recognition.suite());
        if (!organization.equals(recognition.organization())) {
            throw new DirectoryException("Reconnaissance d'une autre organisation : " + recognition.organization());
        }
        String partner = recognition.partner().fingerprint();
        if (partner.equals(organizationKey.fingerprint())) {
            throw new DirectoryException("Une organisation ne se reconnaît pas elle-même");
        }
        UUID key = key(partner);
        long highest = serials.highest(key).orElse(0);
        if (recognition.serial() < highest) {
            throw new DirectoryException("Retour en arrière refusé pour la reconnaissance de « "
                    + recognition.partner().organization() + " » : n° " + recognition.serial() + ", n° " + highest
                    + " déjà vu");
        }
        String digest = EntryVerifier.digest(document.payload());
        if (recognition.serial() == highest) {
            Optional<String> known = serials.digest(key);
            if (known.isPresent() && !known.get().equals(digest)) {
                throw new DirectoryException("Reconnaissance n° " + recognition.serial() + " de « "
                        + recognition.partner().organization() + " » différente de celle déjà vue : refusée");
            }
        }
        serials.record(key, recognition.serial(), digest);
        return recognition;
    }

    /**
     * Clé de mémoire des numéros pour un partenaire : UUID de version 3, qui ne peut pas coïncider avec l'identifiant
     * (aléatoire, version 4) d'une box.
     */
    static UUID key(String partnerFingerprint) {
        return UUID.nameUUIDFromBytes(("imagunity-reconnaissance:" + partnerFingerprint)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Vrai si ce document est une reconnaissance (et non une entrée de box), sans le vérifier. */
    static boolean isRecognition(SignedDocument document) {
        try {
            return Recognition.TYPE.equals(Json.MAPPER.readTree(document.payload()).path("type").asString(""));
        } catch (tools.jackson.core.JacksonException e) {
            return false;
        }
    }
}
