package com.imagunity.directory;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Compte rendu d'une box au service d'annuaire : version de configuration centralisée qu'elle applique, et dernier
 * refus éventuel. Signé par l'identité de la box : le service le vérifie d'après l'entrée d'annuaire de la box, sans
 * compte ni mot de passe. Ne contient aucune donnée de patient.
 *
 * @param type        {@link #TYPE}
 * @param boxId       box
 * @param applied     version appliquée, ou 0 (configuration locale)
 * @param appliedAt   date d'application de cette version, ou {@code null}
 * @param refused     dernière version refusée, ou 0
 * @param refusal     raison du refus, ou {@code null}
 * @param reportedAt  date du compte rendu (les comptes rendus plus anciens que le dernier reçu sont ignorés)
 * @param protocols   versions du protocole que parle la box : le responsable les reporte dans son entrée au
 *                    renouvellement
 * @param nodes       état des appareils DICOM du site (essais de connexion, envois reçus), si la configuration
 *                    centrale en déclare
 * @param answered    identifiant de la dernière demande d'essai traitée ({@link TestRequest}), ou {@code null}
 */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public record ConfigurationReport(String type, UUID boxId, long applied,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) Instant appliedAt, long refused,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String refusal, Instant reportedAt,
                                  @JsonInclude(JsonInclude.Include.NON_EMPTY) java.util.List<String> protocols,
                                  @JsonInclude(JsonInclude.Include.NON_EMPTY) java.util.List<NodeCheck> nodes,
                                  @JsonInclude(JsonInclude.Include.NON_NULL) String answered) {

    public static final String TYPE = "imagunity.configuration.report";

    public ConfigurationReport {
        Objects.requireNonNull(boxId, "boxId");
        Objects.requireNonNull(reportedAt, "reportedAt");
        if (refusal != null && refusal.length() > 500) {
            refusal = refusal.substring(0, 500);
        }
        protocols = Protocols.of(protocols);
        nodes = nodes == null ? java.util.List.of() : java.util.List.copyOf(nodes);
        if (answered != null && answered.length() > 64) {
            answered = answered.substring(0, 64);
        }
    }

    /** Compte rendu sans demande d'essai traitée. */
    public ConfigurationReport(String type, UUID boxId, long applied, Instant appliedAt, long refused, String refusal,
                               Instant reportedAt, java.util.List<String> protocols, java.util.List<NodeCheck> nodes) {
        this(type, boxId, applied, appliedAt, refused, refusal, reportedAt, protocols, nodes, null);
    }

    /** Compte rendu sans état des appareils. */
    public ConfigurationReport(String type, UUID boxId, long applied, Instant appliedAt, long refused, String refusal,
                               Instant reportedAt, java.util.List<String> protocols) {
        this(type, boxId, applied, appliedAt, refused, refusal, reportedAt, protocols, java.util.List.of(), null);
    }

    /** Compte rendu sans version de protocole annoncée (box antérieure). */
    public ConfigurationReport(String type, UUID boxId, long applied, Instant appliedAt, long refused, String refusal,
                               Instant reportedAt) {
        this(type, boxId, applied, appliedAt, refused, refusal, reportedAt, java.util.List.of());
    }

    public static ConfigurationReport of(UUID boxId, long applied, Instant appliedAt, long refused, String refusal,
                                         Instant reportedAt) {
        return new ConfigurationReport(TYPE, boxId, applied, appliedAt, refused, refusal, reportedAt,
                Protocols.SUPPORTED);
    }

    /** Compte rendu avec l'état des appareils du site. */
    public ConfigurationReport withNodes(java.util.List<NodeCheck> checks) {
        return new ConfigurationReport(type, boxId, applied, appliedAt, refused, refusal, reportedAt, protocols,
                checks, answered);
    }

    /** Compte rendu qui répond à cette demande d'essai. */
    public ConfigurationReport withAnswered(String requestId) {
        return new ConfigurationReport(type, boxId, applied, appliedAt, refused, refusal, reportedAt, protocols, nodes,
                requestId);
    }

    /** Signé par la box. */
    public SignedDocument sign(HybridKeyPair boxKeys) {
        return SignedDocument.sign(Json.write(this), boxKeys);
    }

    /**
     * Vérifie un compte rendu : type, et signature par la clé de la box nommée dans le compte rendu.
     *
     * @param boxKey clé de signature de cette box, d'après son entrée d'annuaire
     */
    public static ConfigurationReport verify(SignedDocument document, java.util.function.Function<UUID,
            java.util.Optional<HybridPublicKey>> boxKey) {
        ConfigurationReport report = Json.read(document.payload(), ConfigurationReport.class,
                "Compte rendu de configuration");
        if (!TYPE.equals(report.type())) {
            throw new DirectoryException("Document de type « " + report.type() + " », compte rendu attendu");
        }
        HybridPublicKey key = boxKey.apply(report.boxId()).orElseThrow(() -> new DirectoryException(
                "Box inconnue de l'annuaire : " + report.boxId()));
        if (!document.isSignedBy(key)) {
            throw new DirectoryException("Compte rendu non signé par la box " + report.boxId());
        }
        return report;
    }
}
