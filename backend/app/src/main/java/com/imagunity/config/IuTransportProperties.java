package com.imagunity.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Transport IU-T ({@code imagunity.transport.iu.*}), à la place de simplex-chat. Exige l'annuaire signé : l'appairage,
 * les clés et les adresses de file en viennent.
 *
 * @param enabled   transport IU-T plutôt que SimpleX
 * @param relay     relais IU-T de l'organisation
 * @param stateDir  état durable du transport (file de réception, contacts, transferts en cours)
 * @param ttl       durée de séjour demandée pour les morceaux sur le relais
 * @param parallel  transferts de fichiers simultanés
 * @param keepState conservation de l'état d'un transfert terminé, et d'un message jamais déposé
 */
@ConfigurationProperties("imagunity.transport.iu")
public record IuTransportProperties(
        @DefaultValue("false") boolean enabled,
        URI relay,
        @DefaultValue("./data/iu") Path stateDir,
        @DefaultValue("72h") Duration ttl,
        @DefaultValue("2") int parallel,
        @DefaultValue("8d") Duration keepState) {
}
