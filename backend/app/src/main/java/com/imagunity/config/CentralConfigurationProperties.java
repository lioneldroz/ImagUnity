package com.imagunity.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration centralisée ({@code imagunity.central-configuration.*}, docs/reference/configuration-centralisee.md) : règles de
 * priorité et de routage publiées par le service d'annuaire, signées par le responsable. Exige l'annuaire.
 *
 * @param enabled appliquer les documents publiés ; faux : la box garde sa configuration locale (verrouillage local,
 *                par exemple pour une box de démonstration)
 * @param file    document appliqué, gardé pour le redémarrage
 */
@ConfigurationProperties("imagunity.central-configuration")
public record CentralConfigurationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("./data/configuration-centrale.json") Path file) {
}
