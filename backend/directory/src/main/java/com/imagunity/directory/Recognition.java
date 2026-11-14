package com.imagunity.directory;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Reconnaissance d'une organisation partenaire (fédération) : signée par le responsable de l'organisation qui
 * reconnaît, publiée sur son annuaire. Elle fixe la clé du partenaire (identifié par son empreinte, l'identifiant
 * n'étant qu'un libellé), l'annuaire où lire ses entrées, les relais qu'il peut utiliser, et le périmètre de
 * l'échange en groupes. Mêmes règles que les entrées : numéro croissant, validité, révocation.
 *
 * @param organization organisation qui reconnaît (celle de la clé qui signe)
 * @param partner      organisation reconnue
 * @param scope        groupes autorisés de part et d'autre ; vide : aucun échange
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Recognition(String suite, String type, String organization, Partner partner, Scope scope, long serial,
                          Instant validFrom, Instant validUntil, boolean revoked,
                          @JsonInclude(JsonInclude.Include.NON_NULL) Instant revokedAt,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String revocationReason) {

    public static final String TYPE = "recognition";
    /** « Tous les groupes » dans un périmètre. */
    public static final String ALL = "*";
    static final int MAX_RELAYS = 16;

    /**
     * Organisation reconnue.
     *
     * @param organization identifiant publié par le partenaire (celui de ses entrées)
     * @param key          sa clé de responsable
     * @param directory    adresse de son service d'annuaire, d'où le service local relaie ses entrées
     * @param relays       relais qu'il peut utiliser : une de ses entrées publiant une autre adresse est écartée
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Partner(String organization, HybridPublicKey.Encoded key, String directory, List<String> relays) {

        public Partner {
            Organization.requireValidId(organization);
            Objects.requireNonNull(key, "key");
            HybridPublicKey.decode(key);
            directory = url(directory, "Annuaire du partenaire");
            if (relays == null || relays.isEmpty() || relays.size() > MAX_RELAYS) {
                throw new IllegalArgumentException("Relais du partenaire : de 1 à " + MAX_RELAYS);
            }
            List<String> normalized = new ArrayList<>();
            for (String relay : relays) {
                String r = url(relay, "Relais du partenaire");
                if (normalized.contains(r)) {
                    throw new IllegalArgumentException("Relais en double : " + r);
                }
                normalized.add(r);
            }
            relays = List.copyOf(normalized);
        }

        /** Clé du partenaire. */
        public HybridPublicKey publicKey() {
            return HybridPublicKey.decode(key);
        }

        /** Empreinte complète de sa clé : ce qui identifie le partenaire. */
        public String fingerprint() {
            return publicKey().fingerprint();
        }

        /** Empreinte courte, à comparer hors bande avec le responsable partenaire. */
        public String shortCode() {
            return publicKey().shortCode();
        }

        /** Vrai si cette adresse (file de messages d'une box) est sur un des relais autorisés. */
        public boolean allows(String address) {
            return address != null && relays.stream().anyMatch(r -> address.equals(r) || address.startsWith(r + "/"));
        }
    }

    /**
     * Périmètre de l'échange : groupes locaux autorisés à échanger avec le partenaire, groupes du partenaire acceptés.
     * {@code *} : tous ; liste vide : aucun.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scope(List<String> localGroups, List<String> partnerGroups) {

        public Scope {
            localGroups = groups(localGroups);
            partnerGroups = groups(partnerGroups);
        }

        /** Aucun échange. */
        public static Scope none() {
            return new Scope(List.of(), List.of());
        }

        public boolean allowsLocal(Collection<String> boxGroups) {
            return allows(localGroups, boxGroups);
        }

        public boolean allowsPartner(Collection<String> boxGroups) {
            return allows(partnerGroups, boxGroups);
        }

        /** Vrai si le périmètre ne permet aucun échange. */
        public boolean empty() {
            return localGroups.isEmpty() || partnerGroups.isEmpty();
        }

        private static boolean allows(List<String> scope, Collection<String> boxGroups) {
            return scope.contains(ALL) || boxGroups != null && boxGroups.stream().anyMatch(scope::contains);
        }

        private static List<String> groups(List<String> groups) {
            if (groups == null || groups.isEmpty()) {
                return List.of();
            }
            if (groups.contains(ALL)) {
                if (groups.size() > 1) {
                    throw new IllegalArgumentException("« * » (tous les groupes) ne se combine pas avec d'autres");
                }
                return List.of(ALL);
            }
            return BoxEntry.groups(groups);
        }
    }

    public Recognition {
        Objects.requireNonNull(partner, "partner");
        Objects.requireNonNull(validFrom, "validFrom");
        Objects.requireNonNull(validUntil, "validUntil");
        Organization.requireValidId(organization);
        scope = scope == null ? Scope.none() : scope;
        if (!TYPE.equals(type)) {
            throw new IllegalArgumentException("Document d'un autre type que « recognition » : " + type);
        }
        if (serial < 1) {
            throw new IllegalArgumentException("Le numéro d'émission commence à 1");
        }
        if (!validUntil.isAfter(validFrom)) {
            throw new IllegalArgumentException("Fin de validité antérieure au début");
        }
        if (revocationReason != null && revocationReason.length() > 500) {
            throw new IllegalArgumentException("Motif de révocation trop long (500 caractères au plus)");
        }
    }

    /** Première reconnaissance de ce partenaire. */
    static Recognition first(String organization, Partner partner, Scope scope, Instant now, Duration validity) {
        return new Recognition(Suite.ID, TYPE, organization, partner, scope, 1, now, now.plus(validity), false, null,
                null);
    }

    /** Réémission (renouvellement, changement de périmètre ou de relais) : numéro suivant. */
    Recognition next(Partner newPartner, Scope newScope, Instant now, Duration validity) {
        if (!newPartner.fingerprint().equals(partner.fingerprint())) {
            throw new IllegalArgumentException("Réémission pour une autre clé de partenaire");
        }
        return new Recognition(suite, TYPE, organization, newPartner, newScope, serial + 1, now, now.plus(validity),
                false, null, null);
    }

    /** Révocation : numéro suivant, l'accord cesse aussitôt. */
    Recognition revoke(Instant at, String reason) {
        return new Recognition(suite, TYPE, organization, partner, scope, serial + 1, validFrom, validUntil, true, at,
                reason);
    }

    /** État de l'accord à cette date (mêmes états que les entrées de box). */
    public BoxEntry.Status statusAt(Instant now) {
        if (revoked) {
            return BoxEntry.Status.REVOKED;
        }
        if (now.isBefore(validFrom)) {
            return BoxEntry.Status.NOT_YET_VALID;
        }
        return now.isBefore(validUntil) ? BoxEntry.Status.ACTIVE : BoxEntry.Status.EXPIRED;
    }

    /** Vrai si toutes les adresses de cette entrée sont sur les relais autorisés du partenaire. */
    public boolean relaysAllowed(BoxEntry entry) {
        Map<String, String> transport = entry.transport();
        return transport.values().stream().allMatch(partner::allows);
    }

    private static String url(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " manquant");
        }
        String v = value.strip().replaceAll("/+$", "");
        URI uri;
        try {
            uri = URI.create(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(what + " : adresse invalide « " + value + " »");
        }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException(what + " : adresse http(s) attendue, sans paramètres « " + value
                    + " »");
        }
        return v;
    }
}
