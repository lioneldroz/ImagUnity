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
    ReplayStore replayStore(DataSource dataSource, Clock clock) {
        return new ReplayStore(dataSource, clock);
    }

    @Bean
    RelayLease relayLease(DataSource dataSource, Clock clock, RelayProperties properties) {
        LOG.log(Level.INFO, "Instance du relais : {0}", properties.instance());
        return new RelayLease(dataSource, clock, properties.instance());
    }

    @Bean
    RequestAuthenticator requestAuthenticator(LocalDirectory directory, ReplayStore replay, Clock clock) {
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

    /** Contenu des morceaux : le disque de {@link ChunkFiles}, ou un stockage S3 partagé par les instances. */
    @Bean
    @org.springframework.context.annotation.Primary
    ChunkContents chunkContents(ChunkFiles files, RelayProperties properties) throws IOException {
        RelayProperties.Storage storage = properties.storage();
        if (!storage.s3()) {
            if (!"disk".equalsIgnoreCase(storage.type())) {
                throw new IllegalStateException("Stockage des morceaux inconnu : " + storage.type() + " (disk ou s3)");
            }
            LOG.log(Level.INFO, "Morceaux sur disque : {0}", files.root());
            return files;
        }
        if (storage.accessKey() == null || storage.secretKey() == null) {
            throw new IllegalStateException("Stockage S3 sans compte : IMAGUNITY_RELAY_S3_ACCESS_KEY et "
                    + "IMAGUNITY_RELAY_S3_SECRET_KEY");
        }
        S3ChunkContents s3 = new S3ChunkContents(S3ChunkContents.client(storage.endpoint(), storage.region(),
                storage.accessKey(), storage.secretKey(), storage.pathStyle()), storage.bucket(), storage.prefix());
        s3.ensureBucket();
        LOG.log(Level.INFO, "Morceaux sur le stockage {0} ({1})", s3, storage.endpoint());
        return s3;
    }

    @Bean
    ChunkService chunkService(ChunkStore store, ChunkContents contents, RelayProperties properties, Clock clock) {
        return new ChunkService(store, contents, properties, clock);
    }

    @Bean
    QueueStore queueStore(DataSource dataSource) {
        return new QueueStore(dataSource);
    }

    /** Clés d'adresse IU-T d'une entrée d'annuaire : {@code iu}, {@code iu-2}… (une par relais de la box). */
    static final java.util.regex.Pattern IU_ADDRESS = java.util.regex.Pattern.compile("iu(?:-([2-9]|[1-9][0-9]))?");

    @Bean
    QueueService queueService(QueueStore store, RelayProperties properties, Clock clock, LocalDirectory directory) {
        return new QueueService(store, properties, clock,
                box -> directory.entry(box).map(e -> e.transport().entrySet().stream()
                        .filter(t -> IU_ADDRESS.matcher(t.getKey()).matches()).map(java.util.Map.Entry::getValue)
                        .toList()).orElse(java.util.List.of()));
    }

    @Bean
    Purge purge(ChunkService chunks, QueueService queues, ReplayStore replay, RelayLease lease) {
        return new Purge(chunks, queues, replay, lease);
    }

    /**
     * Purge des morceaux, des messages et des identifiants de requête expirés, chaque minute, par une seule instance
     * à la fois (bail en base, repris par une autre si cette instance s'arrête).
     */
    static final class Purge {

        static final String LEASE = "purge";
        static final java.time.Duration LEASE_FOR = java.time.Duration.ofMinutes(3);

        private final ChunkService chunks;
        private final QueueService queues;
        private final ReplayStore replay;
        private final RelayLease lease;

        Purge(ChunkService chunks, QueueService queues, ReplayStore replay, RelayLease lease) {
            this.chunks = chunks;
            this.queues = queues;
            this.replay = replay;
            this.lease = lease;
        }

        @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
        void run() {
            if (!lease.acquire(LEASE, LEASE_FOR)) {
                return; // une autre instance purge
            }
            chunks.purge();
            queues.purge();
            replay.purge();
        }
    }
}
