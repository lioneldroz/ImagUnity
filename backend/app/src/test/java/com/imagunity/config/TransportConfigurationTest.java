package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.EntryCache;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.transport.DisabledTransport;
import com.imagunity.transport.TransportPort;
import com.imagunity.transport.iu.IuTransport;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.SmartLifecycle;

class TransportConfigurationTest {

    private final TransportConfiguration configuration = new TransportConfiguration();
    private static final IuTransportProperties NO_RELAY = new IuTransportProperties(null, Path.of("x"),
            Duration.ofHours(72), 2, Duration.ofDays(8), true, "auto");

    @TempDir
    Path dir;

    private TransportPort transport(IuTransportProperties properties) {
        return configuration.transport(properties, provider(BoxIdentity.class, null),
                provider(LocalDirectory.class, null), Clock.systemUTC());
    }

    private static <T> ObjectProvider<T> provider(Class<T> type, T bean) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (bean != null) {
            factory.addBean("bean", bean);
        }
        return factory.getBeanProvider(type);
    }

    private static LocalDirectory emptyDirectory() {
        EntryCache none = new EntryCache() {
            @Override
            public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
            }

            @Override
            public List<SignedDocument> entries() {
                return List.of();
            }
        };
        return new LocalDirectory(new EntryVerifier("chu-exemple", new Organization("chu-exemple", HybridKeyPair.generate()).publicKey(),
                SerialMemory.inMemory(), Clock.systemUTC()), none, Clock.systemUTC());
    }

    @Test
    void iuTransportNeedsTheDirectory() throws IOException {
        // Dossier hors de @TempDir : le transport démarré peut encore y écrire en arrière-plan à la fin du test.
        Path state = java.nio.file.Files.createTempDirectory("iu-etat-");
        var iu = new IuTransportProperties(URI.create("http://relais:8443"), state, Duration.ofHours(72), 2,
                Duration.ofDays(8), true, "auto");
        BoxIdentity identity = new BoxIdentity(UUID.randomUUID(), HybridKeyPair.generate(),
                HybridKemKeyPair.generate());

        assertThatThrownBy(() -> transport(iu)).hasMessageContaining("sans annuaire");

        try (TransportPort transport = configuration.transport(iu, provider(BoxIdentity.class, identity),
                provider(LocalDirectory.class, emptyDirectory()), Clock.systemUTC())) {
            assertThat(transport).isInstanceOf(IuTransport.class);
            assertThat(transport.addressKind()).isEqualTo("iu");
            SmartLifecycle lifecycle = configuration.iuTransportLifecycle(transport);
            assertThat(lifecycle.getPhase()).as("après la poignée de main").isGreaterThan(DirectoryConfiguration.PHASE);
            lifecycle.start();
            assertThat(lifecycle.isRunning()).isTrue();
            lifecycle.stop();
            assertThat(lifecycle.isRunning()).isFalse();
        }
        SmartLifecycle other = configuration.iuTransportLifecycle(new DisabledTransport());
        other.start();
        assertThat(other.isRunning()).isTrue();
    }

    @Test
    void chunkSizeIsAutomaticOrOneOfTheAdmittedSizes() {
        assertThat(NO_RELAY.chunkSizeBytes()).isZero();
        assertThat(new IuTransportProperties(null, dir, Duration.ofHours(1), 1, Duration.ofDays(1), true, "1MB")
                .chunkSizeBytes()).isEqualTo(1024 * 1024);
        assertThat(new IuTransportProperties(null, dir, Duration.ofHours(1), 1, Duration.ofDays(1), true, " ")
                .chunkSizeBytes()).isZero();
        assertThat(new IuTransportProperties(null, dir, Duration.ofHours(1), 1, Duration.ofDays(1), true, null)
                .chunkSizeBytes()).isZero();
        assertThatThrownBy(() -> new IuTransportProperties(null, dir, Duration.ofHours(1), 1, Duration.ofDays(1),
                true, "beaucoup").chunkSizeBytes()).hasMessageContaining("invalide");
    }

    @Test
    void disabledTransportWithoutRelay() {
        assertThat(transport(NO_RELAY)).isInstanceOf(DisabledTransport.class);
    }
}

