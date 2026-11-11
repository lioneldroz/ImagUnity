package com.imagunity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Compte opérateur de la console et de l'API ({@code imagunity.console.*}).
 *
 * @param user     identifiant (par défaut {@code operateur})
 * @param password mot de passe, en clair ou haché avec son préfixe ({@code {bcrypt}...}) ; vide : un mot de passe
 *                 aléatoire est généré à chaque démarrage et journalisé
 */
@ConfigurationProperties("imagunity.console")
public record ConsoleAccessProperties(@DefaultValue("operateur") String user, String password) {
}
