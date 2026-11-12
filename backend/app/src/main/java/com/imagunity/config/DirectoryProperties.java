package com.imagunity.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
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
 * @param identityPassphrase     phrase de passe qui chiffre la clé de la box (vide : clé en clair, déconseillé)
 * @param identityPassphraseFile fichier qui la contient (secret Docker, par exemple) ; prioritaire sur la précédente
 * @param encryptionRequired     exige le chiffrement applicatif des paquets : refuse d'envoyer à une box dont l'entrée
 *                               ne publie pas de clés de chiffrement, et de restituer un paquet reçu en clair
 *                               ({@code false} : seulement le temps de migrer un parc)
 */
@ConfigurationProperties("imagunity.directory")
public record DirectoryProperties(
        @DefaultValue("false") boolean enabled,
        URI url,
        Path organizationKey,
        String organizationCode,
        @DefaultValue("./data/identite-box.json") Path identityFile,
        @DefaultValue("5m") Duration syncInterval,
        String identityPassphrase,
        String identityPassphraseFile,
        @DefaultValue("true") boolean encryptionRequired) {

    /** Constructeur utilisé pour lier la configuration. */
    @ConstructorBinding
    public DirectoryProperties {
    }

    /** Annuaire sans phrase de passe pour l'identité (tests). */
    public DirectoryProperties(boolean enabled, URI url, Path organizationKey, String organizationCode,
                               Path identityFile, Duration syncInterval) {
        this(enabled, url, organizationKey, organizationCode, identityFile, syncInterval, null, null, true);
    }

    /** Phrase de passe de l'identité ({@code null} : aucune). */
    char[] passphrase() throws java.io.IOException {
        if (identityPassphraseFile != null && !identityPassphraseFile.isBlank()) {
            String content = java.nio.file.Files.readString(Path.of(identityPassphraseFile)).strip();
            return content.isEmpty() ? null : content.toCharArray();
        }
        return identityPassphrase == null || identityPassphrase.isEmpty() ? null : identityPassphrase.toCharArray();
    }
}
