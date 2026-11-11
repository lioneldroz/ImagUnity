package com.imagunity;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ImagUnityApplicationTest {

    @Test
    void contextLoads() {
    }

    @Test
    void mainStartsTheApplication() {
        // Contexte refermé par le crochet d'arrêt de Spring à la fin de la JVM de test.
        ImagUnityApplication.main(new String[] {
            "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:imagunity-main;DB_CLOSE_DELAY=-1",
            "--spring.jmx.enabled=false"
        });
    }
}
