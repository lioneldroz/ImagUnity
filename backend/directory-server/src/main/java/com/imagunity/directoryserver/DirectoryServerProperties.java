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
 * @param restrictedRead    lecture réservée : listes de l'annuaire lisibles des seuls box actives, partenaires reconnus
 *                          et comptes (à activer une fois toutes les box à jour)
 * @param readerUser        compte de lecture (relais)
 * @param readerPassword    son mot de passe (en clair ou haché) ; vide : pas de compte de lecture
 * @param organizationCode  empreinte courte de la clé de l'organisation, vérifiée hors bande : le service refuse de
 *                          démarrer si la clé installée en a une autre
 */
@ConfigurationProperties("imagunity.directory")
public record DirectoryServerProperties(Path organizationKey, @DefaultValue("responsable") String publisherUser,
                                        String publisherPassword,
                                        @DefaultValue("./mot-de-passe-depot.txt") Path generatedPasswordFile,
                                        @DefaultValue("false") boolean restrictedRead,
                                        @DefaultValue("lecteur") String readerUser, String readerPassword,
                                        String organizationCode) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public DirectoryServerProperties {
    }

    public DirectoryServerProperties(Path organizationKey, String publisherUser, String publisherPassword) {
        this(organizationKey, publisherUser, publisherPassword, Path.of("./mot-de-passe-depot.txt"));
    }

    public DirectoryServerProperties(Path organizationKey, String publisherUser, String publisherPassword,
                                     Path generatedPasswordFile) {
        this(organizationKey, publisherUser, publisherPassword, generatedPasswordFile, false, "lecteur", null, null);
    }
}
