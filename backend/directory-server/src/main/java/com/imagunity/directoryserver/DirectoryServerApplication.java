package com.imagunity.directoryserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Service d'annuaire de l'organisation : publie les entrées de box signées par le responsable. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class DirectoryServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DirectoryServerApplication.class, args);
    }
}
