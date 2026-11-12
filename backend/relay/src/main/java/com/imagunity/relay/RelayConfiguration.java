package com.imagunity.relay;

import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.protocol.ReplayGuard;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Câblage du relais : clé du responsable épinglée, vue locale de l'annuaire et sa synchronisation, stockage des
 * morceaux (IU-FILE) et des files de messages (IU-MSG), vérification des requêtes, purge périodique.
 */
@Configuration(proxyBeanMethods = false)
class RelayConfiguration {

    private static final System.Logger LOG = System.getLogger(RelayConfiguration.class.getName());

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Clé du responsable, épinglée : son empreinte doit être celle confirmée hors bande. */
    @Bean
    KeyFiles.OrganizationPublicKey organizationKey(RelayProperties properties) throws IOException {
        RelayProperties.Directory directory = properties.directory();
        if (directory.url() == null || directory.organizationKey() == null) {
            throw new IllegalStateException("Relais sans annuaire : IMAGUNITY_RELAY_DIRECTORY_URL et "
                    + "IMAGUNITY_RELAY_ORGANIZATION_KEY sont requis");
        }
        KeyFiles.OrganizationPublicKey key = KeyFiles.readOrganizationPublic(directory.organizationKey());
        String actual = key.key().shortCode();
        if (!normalize(actual).equals(normalize(directory.organizationCode()))) {
            throw new IllegalStateException("Empreinte du responsable non confirmée : la clé installée a l'empreinte "
                    + actual + " ; après l'avoir vérifiée hors bande, la recopier dans IMAGUNITY_RELAY_ORGANIZATION_CODE");
        }
        LOG.log(Level.INFO, "Relais de l''organisation « {0} » : clé du responsable épinglée ({1})",
                key.organization(), actual);
        return key;
    }

    static String normalize(String code) {
        return code == null ? "" : code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    @Bean
    RelayDirectoryStore directoryStore(DataSource dataSource, TransactionTemplate transactions) {
        return new RelayDirectoryStore(dataSource, transactions);
    }

    @Bean
    LocalDirectory localDirectory(KeyFiles.OrganizationPublicKey key, RelayDirectoryStore store, Clock clock) {
        return new LocalDirectory(new EntryVerifier(key.organization(), key.key(), store, clock), store, clock);
    }

    @Bean
    DirectoryClient directoryClient(RelayProperties properties) {
        return new DirectoryClient(properties.directory().url(), Duration.ofSeconds(10));
    }

    /** Vue locale chargée au démarrage, puis synchronisée périodiquement (la première fois aussitôt). */
    @Bean
    SmartLifecycle directoryLifecycle(LocalDirectory directory, DirectoryClient client, RelayProperties properties) {
        return new SmartLifecycle() {
            private ScheduledExecutorService scheduler;

            @Override
            public void start() {
                directory.load();
                scheduler = Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("annuaire").daemon().factory());
                scheduler.scheduleWithFixedDelay(() -> {
                    LocalDirectory.SyncStatus status = directory.synchronize(client::fetch);
                    LOG.log(Level.DEBUG, "Annuaire lu : {0} entrée(s) retenue(s)", status.accepted());
                }, 0, properties.directory().syncInterval().toMillis(), TimeUnit.MILLISECONDS);
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
        };
    }

    @Bean
    ReplayGuard replayGuard(Clock clock) {
        return new ReplayGuard(clock);
    }

    @Bean
    RequestAuthenticator requestAuthenticator(LocalDirectory directory, ReplayGuard replay, Clock clock) {
        return new RequestAuthenticator(directory, replay, clock);
    }

    @Bean
    ChunkStore chunkStore(DataSource dataSource) {
        return new ChunkStore(dataSource);
    }

    @Bean
    ChunkFiles chunkFiles(RelayProperties properties) throws IOException {
        return new ChunkFiles(properties.storageDir());
    }

    @Bean
    ChunkService chunkService(ChunkStore store, ChunkFiles files, RelayProperties properties, Clock clock) {
        return new ChunkService(store, files, properties, clock);
    }

    @Bean
    QueueStore queueStore(DataSource dataSource) {
        return new QueueStore(dataSource);
    }

    @Bean
    QueueService queueService(QueueStore store, RelayProperties properties, Clock clock) {
        return new QueueService(store, properties, clock);
    }

    @Bean
    Purge purge(ChunkService chunks, QueueService queues) {
        return new Purge(chunks, queues);
    }

    /** Purge des morceaux et des messages expirés, chaque minute. */
    static final class Purge {

        private final ChunkService chunks;
        private final QueueService queues;

        Purge(ChunkService chunks, QueueService queues) {
            this.chunks = chunks;
            this.queues = queues;
        }

        @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
        void run() {
            chunks.purge();
            queues.purge();
        }
    }
}
