package com.imagunity.directoryserver;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Réglages du service d'annuaire ({@code imagunity.directory.*}).
 *
 * @param organizationKey   clé publique de l'organisation ({@code .pub.json})
 * @param publisherUser     compte de dépôt du responsable
 * @param publisherPassword son mot de passe (en clair ou haché {@code {bcrypt}...}) ; vide : généré à chaque démarrage
 * @param generatedPasswordFile fichier où écrire le mot de passe généré, lisible du seul compte du service (jamais
 *                          dans les journaux)
 */
@ConfigurationProperties("imagunity.directory")
public record DirectoryServerProperties(Path organizationKey, @DefaultValue("responsable") String publisherUser,
                                        String publisherPassword,
                                        @DefaultValue("./mot-de-passe-depot.txt") Path generatedPasswordFile) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DirectoryServerProperties {
    }

    public DirectoryServerProperties(Path organizationKey, String publisherUser, String publisherPassword) {
        this(organizationKey, publisherUser, publisherPassword, Path.of("./mot-de-passe-depot.txt"));
    }
}
