package com.imagunity.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Transport IU-T ({@code imagunity.transport.iu.*}). Exige l'annuaire signé : l'appairage, les clés et les adresses
 * de file en viennent.
 *
 * @param relay     relais IU-T de l'organisation ; sans relais, transport désactivé (les transferts échouent)
 * @param relays    plusieurs relais indépendants (redondance), le premier étant le principal ; remplace {@code relay}
 * @param replicas  copies des morceaux de chaque envoi, sur autant de relais (0 : une par relais)
 * @param stateDir  état durable du transport (file de réception, contacts, transferts en cours)
 * @param ttl       durée de séjour demandée pour les morceaux sur le relais
 * @param parallel  transferts de fichiers simultanés
 * @param keepState conservation de l'état d'un transfert terminé, et d'un message jamais déposé
 * @param early     annonce anticipée : le destinataire télécharge pendant le téléversement (spécification § 6.6)
 * @param chunkSize taille des morceaux : {@code auto} (adaptée au débit mesuré de la liaison), ou 256KB, 1MB, 4MB,
 *                  8MB
 */
@ConfigurationProperties("imagunity.transport.iu")
public record IuTransportProperties(
        URI relay,
        @DefaultValue("./data/iu") Path stateDir,
        @DefaultValue("72h") Duration ttl,
        @DefaultValue("2") int parallel,
        @DefaultValue("8d") Duration keepState,
        @DefaultValue("true") boolean early,
        @DefaultValue("auto") String chunkSize,
        List<URI> relays,
        @DefaultValue("0") int replicas) {

    @ConstructorBinding
    public IuTransportProperties {
        relays = relays == null ? List.of() : relays.stream().filter(java.util.Objects::nonNull)
                .filter(r -> !r.toString().isBlank()).toList();
    }

    public IuTransportProperties(URI relay, Path stateDir, Duration ttl, int parallel, Duration keepState,
                                 boolean early, String chunkSize) {
        this(relay, stateDir, ttl, parallel, keepState, early, chunkSize, List.of(), 0);
    }

    /** Relais configurés : {@code relays}, sinon {@code relay} ; vide si aucun (transport désactivé). */
    public List<URI> allRelays() {
        if (!relays.isEmpty()) {
            return relays;
        }
        return relay == null || relay.toString().isBlank() ? List.of() : List.of(relay);
    }

    /** Taille de morceau imposée, ou 0 pour l'adapter à la liaison. */
    public int chunkSizeBytes() {
        if (chunkSize == null || chunkSize.isBlank() || chunkSize.equalsIgnoreCase("auto")) {
            return 0;
        }
        try {
            return Math.toIntExact(org.springframework.util.unit.DataSize.parse(chunkSize.strip()).toBytes());
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new IllegalArgumentException("Taille de morceau IU-T invalide : " + chunkSize
                    + " (auto, 256KB, 1MB, 4MB ou 8MB)", e);
        }
    }
}
