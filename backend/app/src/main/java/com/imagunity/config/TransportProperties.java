package com.imagunity.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Transport SimpleX ({@code imagunity.transport.simplex.*}).
 *
 * @param enabled        pilote le client simplex-chat ; sinon, transport désactivé (les transferts échouent)
 * @param url            API WebSocket du client simplex-chat (même machine)
 * @param startupTimeout délai d'attente du client au démarrage de la box (il peut démarrer après elle)
 * @param commandTimeout délai maximal de réponse à une commande
 */
@ConfigurationProperties("imagunity.transport.simplex")
public record TransportProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("ws://localhost:5225") URI url,
        @DefaultValue("2m") Duration startupTimeout,
        @DefaultValue("60s") Duration commandTimeout) {
}
