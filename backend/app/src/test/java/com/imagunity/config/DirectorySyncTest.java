package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.imagunity.configuration.CentralRules;
import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.HybridKeyPair;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Lecture périodique de l'annuaire et de la configuration centrale. */
class DirectorySyncTest {

    @TempDir
    Path dir;

    @Test
    void anUnexpectedErrorDoesNotEndThePeriodicSynchronization() {
        DirectoryClient client = new DirectoryClient(URI.create("http://127.0.0.1:1"), Duration.ofSeconds(1));
        CentralRules rules = new CentralRules(UUID.randomUUID(), "chu-exemple", HybridKeyPair.generate().publicKey(),
                dir.resolve("configuration-centrale.json"), Clock.systemUTC());

        // Erreur imprévue dans chacune des deux lectures (ici, des dépendances absentes) : journalisée, jamais
        // propagée, sinon le planificateur annulerait toutes les lectures suivantes.
        assertThatCode(() -> DirectoryConfiguration.synchronizeAll(null, client, null, Runnable::run, rules, null))
                .doesNotThrowAnyException();
        assertThatCode(() -> DirectoryConfiguration.synchronizeAll(null, client, null, Runnable::run, null, null))
                .doesNotThrowAnyException();
    }
}
