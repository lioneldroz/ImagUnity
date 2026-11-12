package com.imagunity.relay;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Relais IU-T (spécification {@code docs/specification-protocole.md}) : stockage temporaire de morceaux chiffrés
 * (IU-FILE) et files de messages chiffrés (IU-MSG). Aveugle : il ne détient aucune clé de contenu ; il vérifie
 * seulement des signatures.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class RelayApplication {

    public static void main(String[] args) {
        SpringApplication.run(RelayApplication.class, args);
    }
}
