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
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.transport.DisabledTransport;
import com.imagunity.transport.TransportPort;
import com.imagunity.transport.iu.IuTransport;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.SmartLifecycle;

class TransportConfigurationTest {

    private final TransportConfiguration configuration = new TransportConfiguration();
    private static final IuTransportProperties NO_RELAY = new IuTransportProperties(null, Path.of("x"),
            Duration.ofHours(72), 2, Duration.ofDays(8), true);

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
    void iuTransportNeedsTheDirectory() {
        var iu = new IuTransportProperties(URI.create("http://relais:8443"), dir, Duration.ofHours(72), 2,
                Duration.ofDays(8), true);
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
    void disabledTransportWithoutRelay() {
        assertThat(transport(NO_RELAY)).isInstanceOf(DisabledTransport.class);
    }

    /** Partenaires en mémoire : un même nom ou identifiant de transport est remplacé, comme en base. */
    private static final class Peers implements PeerRepository {
        final Map<String, Peer> byName = new TreeMap<>();

        @Override
        public void save(Peer peer) {
            byName.values().removeIf(p -> p.transportPeerId() == peer.transportPeerId());
            byName.put(peer.name(), peer);
        }

        @Override
        public Optional<Peer> findByName(String name) {
            return Optional.ofNullable(byName.get(name));
        }

        @Override
        public Optional<Peer> findByTransportPeerId(long transportPeerId) {
            return byName.values().stream().filter(p -> p.transportPeerId() == transportPeerId).findFirst();
        }

        @Override
        public List<Peer> findAll() {
            return List.copyOf(byName.values());
        }

        @Override
        public void delete(String name) {
            byName.remove(name);
        }
    }

    @Test
    void switchingToIuKeepsVerifiedPartnersUnderTheirNames() {
        var iu = new IuTransportProperties(URI.create("http://relais:8443"), dir, Duration.ofHours(72), 2,
                Duration.ofDays(8), true);
        BoxIdentity identity = new BoxIdentity(UUID.randomUUID(), HybridKeyPair.generate(),
                HybridKemKeyPair.generate());
        UUID boxB = UUID.randomUUID();
        Instant pairedAt = Instant.parse("2026-11-01T10:00:00Z");
        Peers peers = new Peers();
        peers.save(new Peer("box-b", 2, "B", pairedAt, boxB));
        peers.save(new Peer("attente-1", 1, "inconnu", pairedAt));

        try (TransportPort transport = configuration.transport(iu, provider(BoxIdentity.class, identity),
                provider(LocalDirectory.class, emptyDirectory()), Clock.systemUTC())) {
            SmartLifecycle migration = configuration.iuPeerMigration(transport, peers);
            assertThat(migration.getPhase()).as("avant l'orchestration").isLessThan(OrchestrationConfiguration.PHASE);
            migration.start();
            assertThat(migration.isRunning()).isTrue();
            Peer kept = peers.findByName("box-b").orElseThrow();
            assertThat(kept.boxId()).isEqualTo(boxB);
            assertThat(kept.transportPeerId()).as("contact IU-T, loin des identifiants SimpleX")
                    .isGreaterThanOrEqualTo(1_000_000);
            assertThat(kept.pairedAt()).isEqualTo(pairedAt);
            assertThat(peers.findByName("attente-1")).isEmpty();

            // Une seule fois : un partenaire appairé ensuite n'est plus touché.
            peers.save(new Peer("box-c", 5, "C", pairedAt));
            migration.stop();
            assertThat(migration.isRunning()).isFalse();
            migration.start();
            assertThat(peers.findByName("box-c")).isPresent();
        }
        configuration.iuPeerMigration(new DisabledTransport(), peers).start();
        assertThat(peers.findByName("box-c")).isPresent();
    }

}
