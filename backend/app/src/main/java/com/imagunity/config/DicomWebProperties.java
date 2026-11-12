package com.imagunity.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Compte DICOMweb de la box ({@code imagunity.dicomweb.*}) : le PACS s'en sert pour déposer des instances par
 * STOW-RS. Il n'ouvre que {@code /dicomweb/**}, pas la console.
 *
 * @param user     identifiant (par défaut {@code pacs})
 * @param password mot de passe, en clair ou haché avec son préfixe ({@code {bcrypt}...}) ; vide : pas de compte
 *                 DICOMweb, la réception STOW-RS est fermée
 */
@ConfigurationProperties("imagunity.dicomweb")
public record DicomWebProperties(@DefaultValue("pacs") String user, String password) {

    boolean enabled() {
        return password != null && !password.isBlank();
    }
}
