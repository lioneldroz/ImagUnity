package com.imagunity.directory;

import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Vérifie les entrées de l'annuaire côté box, avec la clé du responsable épinglée à l'installation.
 *
 * <p>Erreurs de sécurité ({@link DirectoryException}) : signature invalide, autre suite ou autre organisation,
 * retour en arrière. Sinon, l'entrée est retenue avec son {@link BoxEntry.Status état} : seule une entrée
 * {@code ACTIVE} permet d'échanger avec la box.
 */
public final class EntryVerifier {

    private final String organization;
    private final HybridPublicKey organizationKey;
    private final SerialMemory serials;
    private final Clock clock;

    public EntryVerifier(String organization, HybridPublicKey organizationKey, SerialMemory serials, Clock clock) {
        this.organization = Objects.requireNonNull(organization, "organization");
        this.organizationKey = Objects.requireNonNull(organizationKey, "organizationKey");
        this.serials = Objects.requireNonNull(serials, "serials");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Vérifie une entrée et retient son numéro.
     *
     * @throws DirectoryException en cas d'erreur de sécurité ou d'entrée illisible
     */
    public Verified verify(SignedDocument document) {
        if (!document.isSignedBy(organizationKey)) {
            throw new DirectoryException("Entrée non signée par le responsable de l'organisation");
        }
        BoxEntry entry = Json.read(document.payload(), BoxEntry.class, "Entrée de box");
        Suite.require(entry.suite());
        if (!organization.equals(entry.organization())) {
            throw new DirectoryException("Entrée d'une autre organisation : " + entry.organization());
        }
        long highest = serials.highest(entry.boxId()).orElse(0);
        if (entry.serial() < highest) {
            throw new DirectoryException("Retour en arrière refusé pour la box " + entry.boxId() + " : entrée n° "
                    + entry.serial() + ", n° " + highest + " déjà vu");
        }
        String digest = digest(document.payload());
        if (entry.serial() == highest) {
            Optional<String> known = serials.digest(entry.boxId());
            if (known.isPresent() && !known.get().equals(digest)) {
                throw new DirectoryException("Entrée n° " + entry.serial() + " de la box " + entry.boxId()
                        + " différente de celle déjà vue sous ce numéro : refusée");
            }
        }
        serials.record(entry.boxId(), entry.serial(), digest);
        return new Verified(entry, entry.statusAt(clock.instant()));
    }

    /** Empreinte SHA-256 (hexadécimal) du contenu signé, indépendante de la signature (ML-DSA n'est pas déterministe). */
    static String digest(String payload) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Entrée vérifiée.
     *
     * @param entry  entrée
     * @param status état à la date de vérification
     */
    public record Verified(BoxEntry entry, BoxEntry.Status status) {

        public boolean usable() {
            return status == BoxEntry.Status.ACTIVE;
        }
    }
}
