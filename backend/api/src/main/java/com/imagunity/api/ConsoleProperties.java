package com.imagunity.api;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de la console ({@code imagunity.console.*}).
 *
 * @param ackOverdue délai au-delà duquel un transfert annoncé sans accusé est signalé (30 min par défaut)
 */
@ConfigurationProperties("imagunity.console")
public record ConsoleProperties(Duration ackOverdue) {

    public ConsoleProperties {
        if (ackOverdue == null) {
            ackOverdue = Duration.ofMinutes(30);
        }
    }
}
