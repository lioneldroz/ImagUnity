package com.imagunity.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Compte opérateur de la console et de l'API ({@code imagunity.console.*}).
 *
 * @param user     identifiant (par défaut {@code operateur})
 * @param password          mot de passe, en clair ou haché avec son préfixe ({@code {bcrypt}...}) ; vide : un mot
 *                          de passe aléatoire est généré à chaque démarrage
 * @param generatedPasswordFile fichier où écrire le mot de passe généré (lisible du seul compte de la box), plutôt
 *                          que dans les journaux, souvent collectés et conservés ailleurs
 */
@ConfigurationProperties("imagunity.console")
public record ConsoleAccessProperties(@DefaultValue("operateur") String user, String password,
                                      @DefaultValue("./data/mot-de-passe-console.txt") Path generatedPasswordFile) {

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public ConsoleAccessProperties {
    }

    public ConsoleAccessProperties(String user, String password) {
        this(user, password, Path.of("./data/mot-de-passe-console.txt"));
    }
}
