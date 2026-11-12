package com.imagunity.config;

import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.orchestration.DirectoryPeerAuthorization;
import com.imagunity.orchestration.MessageSigner;
import com.imagunity.orchestration.PeerAuthorization;
import com.imagunity.orchestration.PeerHandshake;
import com.imagunity.persistence.JdbcDirectoryStore;
import com.imagunity.transport.TransportPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Annuaire signé (activé par {@code imagunity.directory.enabled=true}) : clé épinglée du responsable, identité de la
 * box, vue locale de l'annuaire et sa synchronisation, poignée de main avec les partenaires, autorisation.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "imagunity.directory.enabled", havingValue = "true")
class DirectoryConfiguration {

    /** Démarre après l'orchestration : la poignée de main s'abonne au transport après l'appairage. */
    static final int PHASE = OrchestrationConfiguration.PHASE + 1;

    private static final Logger LOG = LoggerFactory.getLogger(DirectoryConfiguration.class);

    /** Clé du responsable, épinglée : son empreinte doit être celle confirmée hors bande par l'installateur. */
    @Bean
    KeyFiles.OrganizationPublicKey organizationKey(DirectoryProperties properties) throws IOException {
        if (properties.organizationKey() == null || properties.url() == null) {
            throw new IllegalStateException("Annuaire activé sans service ni clé du responsable "
                    + "(IMAGUNITY_DIRECTORY_URL, IMAGUNITY_DIRECTORY_ORGANIZATION_KEY)");
        }
        KeyFiles.OrganizationPublicKey key = KeyFiles.readOrganizationPublic(properties.organizationKey());
        String actual = key.key().shortCode();
        if (!normalize(actual).equals(normalize(properties.organizationCode()))) {
            throw new IllegalStateException("Empreinte du responsable non confirmée : la clé installée a l'empreinte "
                    + actual + " ; après l'avoir vérifiée hors bande avec le responsable, la recopier dans "
                    + "IMAGUNITY_DIRECTORY_ORGANIZATION_CODE");
        }
        LOG.info("Annuaire de l'organisation « {} » : clé du responsable épinglée ({})", key.organization(), actual);
        return key;
    }

    static String normalize(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    /** Identité de la box : créée au premier démarrage, puis relue. */
    @Bean
    BoxIdentity boxIdentity(DirectoryProperties properties) throws IOException {
        return loadOrCreateIdentity(properties.identityFile(), properties.passphrase());
    }

    static BoxIdentity loadOrCreateIdentity(Path keysFile) throws IOException {
        return loadOrCreateIdentity(keysFile, null);
    }

    /**
     * Identité de la box : créée au premier démarrage (chiffrée si une phrase de passe est fournie), puis relue. Une
     * identité en clair est chiffrée sur place dès qu'une phrase de passe est fournie.
     *
     * @throws IllegalStateException si l'identité est protégée et qu'aucune phrase de passe n'est fournie
     */
    static BoxIdentity loadOrCreateIdentity(Path keysFile, char[] passphrase) throws IOException {
        Path idFile = Path.of(keysFile + ".id");
        if (Files.exists(keysFile)) {
            boolean protectedKey = KeyFiles.isProtected(keysFile);
            if (protectedKey && passphrase == null) {
                throw new IllegalStateException("Identité de la box protégée par phrase de passe : la fournir par "
                        + "IMAGUNITY_DIRECTORY_IDENTITY_PASSPHRASE_FILE (ou ..._PASSPHRASE)");
            }
            if (!protectedKey && passphrase != null) {
                KeyFiles.protect(keysFile, passphrase);
                LOG.info("Identité de la box désormais protégée par phrase de passe");
            } else if (!protectedKey) {
                warnUnprotected();
            }
            BoxIdentity identity = new BoxIdentity(UUID.fromString(Files.readString(idFile, StandardCharsets.UTF_8)
                    .strip()), KeyFiles.readPrivate(keysFile, passphrase));
            LOG.info("Identité de la box {} ({})", identity.boxId(), identity.keys().publicKey().shortCode());
            return identity;
        }
        BoxIdentity identity = new BoxIdentity(UUID.randomUUID(), HybridKeyPair.generate());
        KeyFiles.writePrivate(keysFile, identity.keys(), passphrase);
        Files.writeString(idFile, identity.boxId().toString(), StandardCharsets.UTF_8);
        if (passphrase == null) {
            warnUnprotected();
        }
        LOG.info("Identité de la box créée : {} ({}) — à faire enrôler par le responsable", identity.boxId(),
                identity.keys().publicKey().shortCode());
        return identity;
    }

    private static void warnUnprotected() {
        LOG.warn("Clé de la box NON protégée par phrase de passe : fournir IMAGUNITY_DIRECTORY_IDENTITY_PASSPHRASE_FILE");
    }

    @Bean
    JdbcDirectoryStore directoryStore(DataSource dataSource) {
        return new JdbcDirectoryStore(dataSource);
    }

    @Bean
    LocalDirectory localDirectory(KeyFiles.OrganizationPublicKey key, JdbcDirectoryStore store, Clock clock) {
        // Le cache est relu au démarrage du cycle de vie, une fois les migrations de la base faites.
        return new LocalDirectory(new EntryVerifier(key.organization(), key.key(), store, clock), store, clock);
    }

    /** Annonces et accusés signés par cette box, vérifiés avec l'annuaire à la réception. */
    @Bean
    MessageSigner messageSigner(BoxIdentity identity, LocalDirectory directory) {
        return new MessageSigner(identity, directory);
    }

    @Bean
    PeerAuthorization peerAuthorization(LocalDirectory directory) {
        return new DirectoryPeerAuthorization(directory);
    }

    @Bean
    PeerHandshake peerHandshake(PeerRepository peers, TransportPort transport, BoxIdentity identity,
                                LocalDirectory directory, ExecutorService orchestrationExecutor, Clock clock,
                                OrchestrationProperties orchestration) {
        return new PeerHandshake(peers, transport, identity, directory, orchestrationExecutor, clock,
                orchestration.pairingTimeout());
    }

    @Bean
    DirectoryClient directoryClient(DirectoryProperties properties) {
        return new DirectoryClient(properties.url(), Duration.ofSeconds(10));
    }

    /** Synchronisation périodique (la première au démarrage), puis nouvelle poignée de main avec les non-liés. */
    @Bean
    SmartLifecycle directoryLifecycle(LocalDirectory directory, DirectoryClient client, PeerHandshake handshake,
                                      DirectoryProperties properties, ExecutorService orchestrationExecutor) {
        return new SmartLifecycle() {
            private ScheduledExecutorService scheduler;

            @Override
            public void start() {
                directory.load();
                handshake.start();
                scheduler = Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("annuaire").daemon().factory());
                scheduler.scheduleWithFixedDelay(() -> synchronize(directory, client, handshake, orchestrationExecutor),
                        0, properties.syncInterval().toMillis(), TimeUnit.MILLISECONDS);
            }

            @Override
            public void stop() {
                scheduler.shutdownNow();
                scheduler = null;
            }

            @Override
            public boolean isRunning() {
                return scheduler != null;
            }

            @Override
            public int getPhase() {
                return PHASE;
            }
        };
    }

    static void synchronize(LocalDirectory directory, DirectoryClient client, PeerHandshake handshake,
                            java.util.concurrent.Executor executor) {
        LocalDirectory.SyncStatus status = directory.synchronize(client::fetch);
        if (status.error() == null) {
            LOG.debug("Annuaire lu : {} entrée(s) retenue(s), {} écartée(s)", status.accepted(), status.rejected());
        }
        executor.execute(handshake::greetUnbound);
    }
}
