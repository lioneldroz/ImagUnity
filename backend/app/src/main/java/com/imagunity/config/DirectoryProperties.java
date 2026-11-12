package com.imagunity.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Annuaire signé, côté box ({@code imagunity.directory.*}).
 *
 * @param enabled          active l'annuaire : seuls les partenaires liés à une box active de l'annuaire sont acceptés
 * @param url              service d'annuaire de l'organisation
 * @param organizationKey  clé publique du responsable ({@code .pub.json})
 * @param organizationCode empreinte courte du responsable, recopiée par l'installateur après vérification hors bande ;
 *                         la box refuse de démarrer si elle ne correspond pas à la clé
 * @param identityFile     clé privée de la box (créée au premier démarrage) ; son identifiant est à côté ({@code .id})
 * @param syncInterval     intervalle entre deux lectures de l'annuaire
 */
@ConfigurationProperties("imagunity.directory")
public record DirectoryProperties(
        @DefaultValue("false") boolean enabled,
        URI url,
        Path organizationKey,
        String organizationCode,
        @DefaultValue("./data/identite-box.json") Path identityFile,
        @DefaultValue("5m") Duration syncInterval) {
}
