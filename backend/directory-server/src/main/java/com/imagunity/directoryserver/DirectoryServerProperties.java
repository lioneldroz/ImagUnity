package com.imagunity.directoryserver;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Réglages du service d'annuaire ({@code imagunity.directory.*}).
 *
 * @param organizationKey   clé publique de l'organisation ({@code .pub.json})
 * @param publisherUser     compte de dépôt du responsable
 * @param publisherPassword son mot de passe (en clair ou haché {@code {bcrypt}...}) ; vide : généré et journalisé
 */
@ConfigurationProperties("imagunity.directory")
public record DirectoryServerProperties(Path organizationKey, @DefaultValue("responsable") String publisherUser,
                                        String publisherPassword) {
}
