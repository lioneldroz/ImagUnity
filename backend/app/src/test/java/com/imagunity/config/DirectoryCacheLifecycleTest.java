package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.EntryCache;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.SmartLifecycle;

/**
 * La vue locale de l'annuaire est chargée avant l'orchestration : les messages rejoués par le rattrapage du transport
 * au démarrage (accusés arrivés pendant l'arrêt de la box) sont vérifiés avec les entrées connues.
 */
class DirectoryCacheLifecycleTest {

    @Test
    void cachedEntriesAreLoadedBeforeTheOrchestrationStarts() {
        Instant now = Instant.parse("2026-11-12T10:00:00Z");
        Organization organization = new Organization("chu-exemple", HybridKeyPair.generate());
        UUID partner = UUID.randomUUID();
        SignedDocument entry = organization.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), partner,
                "Site B", Map.of(), now), now, Duration.ofDays(30), 1);
        EntryCache cache = new EntryCache() {
            @Override
            public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
            }

            @Override
            public List<SignedDocument> entries() {
                return List.of(entry);
            }
        };
        Clock clock = Clock.fixed(now.plusSeconds(60), ZoneOffset.UTC);
        LocalDirectory directory = new LocalDirectory(new EntryVerifier("chu-exemple", organization.publicKey(),
                SerialMemory.inMemory(), clock), cache, clock);
        SmartLifecycle lifecycle = new DirectoryConfiguration().directoryCacheLifecycle(directory);

        assertThat(lifecycle.getPhase()).as("avant l'orchestration et son rattrapage")
                .isLessThan(OrchestrationConfiguration.PHASE);
        assertThat(OrchestrationConfiguration.PHASE).isLessThan(DirectoryConfiguration.PHASE);
        assertThat(directory.entry(partner)).isEmpty();

        lifecycle.start();

        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(directory.entry(partner)).isPresent();
        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }
}
