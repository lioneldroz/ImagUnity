package com.imagunity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** Point d'entrée de la box ImagUnity. Le même binaire joue émetteur ou récepteur. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ImagUnityApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImagUnityApplication.class, args);
    }
}
