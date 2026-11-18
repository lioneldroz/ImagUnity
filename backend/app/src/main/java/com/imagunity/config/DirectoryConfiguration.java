package com.imagunity.config;

import com.imagunity.configuration.CentralRules;
import com.imagunity.configuration.ReloadablePriority;
import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.TpmSeal;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.orchestration.DirectoryPacketEncryption;
import com.imagunity.orchestration.DirectoryPeerAuthorization;
import com.imagunity.orchestration.MessageSigner;
import com.imagunity.orchestration.PacketEncryption;
import com.imagunity.orchestration.PeerAuthorization;
import com.imagunity.orchestration.PeerHandshake;
import com.imagunity.persistence.JdbcDirectoryStore;
import com.imagunity.transport.TransportPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
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
import org.springframework.core.env.Environment;

/**
 * Annuaire signé (activé par {@code imagunity.directory.enabled=true}) : clé épinglée du responsable, identité de la
 * box, vue locale de l'annuaire et sa synchronisation, poignée de main avec les partenaires, autorisation, chiffrement
 * applicatif des paquets.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "imagunity.directory.enabled", havingValue = "true")
class DirectoryConfiguration {

    /** Démarre après l'orchestration : la poignée de main s'abonne au transport après l'appairage. */
    static final int PHASE = OrchestrationConfiguration.PHASE + 1;

    /** Vue locale de l'annuaire : chargée avant l'orchestration et le rattrapage des messages. */
    static final int CACHE_PHASE = OrchestrationConfiguration.PHASE - 1;

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
        if (properties.identityTpm()) {
            return loadOrCreateIdentity(properties.identityFile(), properties.passphrase(),
                    new TpmSeal(properties.identityFile(), properties.identityTpmPcrs()));
        }
        return loadOrCreateIdentity(properties.identityFile(), properties.passphrase());
    }

    /**
     * Identité de la box protégée par un secret scellé dans son TPM : secret tiré et scellé au premier démarrage, puis
     * descellé à chaque démarrage. Des clés existantes (en clair, ou chiffrées par la phrase de passe {@code previous})
     * sont chiffrées sur place par ce secret ; la phrase de passe ne sert alors plus.
     *
     * @throws IllegalStateException si le TPM refuse de desceller le secret, ou si des clés chiffrées par une autre
     *                               phrase de passe ne peuvent pas être migrées
     */
    static BoxIdentity loadOrCreateIdentity(Path keysFile, char[] previous, TpmSeal tpm) throws IOException {
        char[] secret;
        if (tpm.exists()) {
            try {
                secret = new String(tpm.unseal(), StandardCharsets.UTF_8).strip().toCharArray();
            } catch (IOException e) {
                throw new IllegalStateException("Le TPM refuse de desceller le secret des clés de la box (autre TPM, "
                        + "ou état de démarrage modifié) : " + e.getMessage(), e);
            }
        } else {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            secret = Base64.getEncoder().encodeToString(random).toCharArray();
            tpm.seal(new String(secret).getBytes(StandardCharsets.UTF_8));
            LOG.info("Secret des clés de la box tiré et scellé dans le TPM");
        }
        migrateToTpm(keysFile, previous, secret, false);
        migrateToTpm(encryptionFileOf(keysFile), previous, secret, true);
        BoxIdentity identity = loadOrCreateIdentity(keysFile, secret);
        LOG.info("Clés de la box protégées par le TPM");
        return identity;
    }

    /** Chiffre par le secret du TPM des clés en clair, ou chiffrées par la phrase de passe précédente. */
    private static void migrateToTpm(Path file, char[] previous, char[] secret, boolean encryption)
            throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        if (!KeyFiles.isProtected(file)) {
            KeyFiles.reprotect(file, null, secret);
            LOG.info("Clés {} désormais protégées par le TPM", file.getFileName());
            return;
        }
        try {
            if (encryption) {
                KeyFiles.readEncryptionPrivate(file, secret);
            } else {
                KeyFiles.readPrivate(file, secret);
            }
            return; // déjà sous le secret du TPM
        } catch (DirectoryException e) {
            if (previous == null) {
                throw new IllegalStateException("Clés " + file.getFileName() + " chiffrées par une phrase de passe : "
                        + "la fournir une dernière fois (IMAGUNITY_DIRECTORY_IDENTITY_PASSPHRASE_FILE) pour les "
                        + "placer sous la protection du TPM", e);
            }
        }
        KeyFiles.reprotect(file, previous, secret);
        LOG.warn("Clés {} désormais protégées par le TPM : la phrase de passe précédente ne sert plus, son secret "
                + "peut être retiré de la configuration", file.getFileName());
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
            if (!Files.exists(idFile)) {
                throw new IllegalStateException("Identifiant de la box absent (" + idFile + ") à côté de ses clés : "
                        + "le restaurer depuis une sauvegarde (il figure aussi dans l'entrée d'annuaire de la box)");
            }
            HybridKeyPair keys = readKeys(keysFile, passphrase);
            UUID boxId = UUID.fromString(Files.readString(idFile, StandardCharsets.UTF_8).strip());
            Path encryptionFile = encryptionFileOf(keysFile);
            HybridKemKeyPair encryption;
            if (Files.exists(encryptionFile)) {
                encryption = readEncryption(encryptionFile, passphrase);
            } else {
                encryption = HybridKemKeyPair.generate();
                KeyFiles.writeEncryptionPrivate(encryptionFile, encryption, passphrase);
                LOG.warn("Clés de chiffrement de la box créées : faire réémettre son entrée d'annuaire par le "
                        + "responsable, à partir d'une nouvelle demande d'enrôlement (console, onglet Annuaire)");
            }
            BoxIdentity identity = new BoxIdentity(boxId, keys, encryption);
            LOG.info("Identité de la box {} ({})", identity.boxId(), identity.keys().publicKey().shortCode());
            return identity;
        }
        // Création : l'identifiant d'abord, puis les clés, chaque fichier écrit d'un coup. Après un arrêt pendant la
        // création, on repart de l'identifiant déjà écrit (la box n'a pas encore pu être enrôlée sans ses clés).
        UUID boxId;
        if (Files.exists(idFile)) {
            boxId = UUID.fromString(Files.readString(idFile, StandardCharsets.UTF_8).strip());
            Files.deleteIfExists(encryptionFileOf(keysFile)); // clés de chiffrement d'une création interrompue
        } else {
            boxId = UUID.randomUUID();
            Path parent = idFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            KeyFiles.writeNew(idFile, boxId.toString());
        }
        BoxIdentity identity = new BoxIdentity(boxId, HybridKeyPair.generate(), HybridKemKeyPair.generate());
        KeyFiles.writeEncryptionPrivate(encryptionFileOf(keysFile), identity.encryption(), passphrase);
        KeyFiles.writePrivate(keysFile, identity.keys(), passphrase);
        if (passphrase == null) {
            warnUnprotected();
        }
        LOG.info("Identité de la box créée : {} ({}) — à faire enrôler par le responsable", identity.boxId(),
                identity.keys().publicKey().shortCode());
        return identity;
    }

    /** Clés de chiffrement de la box, à côté de sa clé de signature. */
    static Path encryptionFileOf(Path keysFile) {
        return Path.of(keysFile + ".chiffrement");
    }

    private static HybridKeyPair readKeys(Path keysFile, char[] passphrase) throws IOException {
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
        return KeyFiles.readPrivate(keysFile, passphrase);
    }

    private static HybridKemKeyPair readEncryption(Path file, char[] passphrase) throws IOException {
        if (!KeyFiles.isProtected(file) && passphrase != null) {
            KeyFiles.protect(file, passphrase);
            LOG.info("Clés de chiffrement de la box désormais protégées par phrase de passe");
        }
        return KeyFiles.readEncryptionPrivate(file, passphrase);
    }

    private static void warnUnprotected() {
        LOG.warn("Clé de la box NON protégée par phrase de passe : fournir IMAGUNITY_DIRECTORY_IDENTITY_PASSPHRASE_FILE");
    }

    @Bean
    JdbcDirectoryStore directoryStore(DataSource dataSource) {
        return new JdbcDirectoryStore(dataSource);
    }

    @Bean
    LocalDirectory localDirectory(KeyFiles.OrganizationPublicKey key, JdbcDirectoryStore store, Clock clock,
            @org.springframework.beans.factory.annotation.Value("${imagunity.directory.freshness-max-age:48h}")
            Duration freshnessMaxAge) {
        // Le cache est relu au démarrage du cycle de vie, une fois les migrations de la base faites.
        // Fédération : reconnaissances signées par le responsable et entrées des partenaires, gardées dans le même
        // cache (un identifiant de box n'appartient qu'à une organisation).
        return new LocalDirectory(new EntryVerifier(key.organization(), key.key(), store, clock), store, clock)
                .federate(new com.imagunity.directory.RecognitionVerifier(key.organization(), key.key(), store), store)
                .checkFreshness(key.organization(), key.key(), freshnessMaxAge);
    }

    /** Annonces et accusés signés par cette box, vérifiés avec l'annuaire à la réception. */
    @Bean
    MessageSigner messageSigner(BoxIdentity identity, LocalDirectory directory) {
        return new MessageSigner(identity, directory);
    }

    /** Ancien réglage qui permettait des paquets en clair, le temps de migrer un parc. */
    static final String ENCRYPTION_REQUIRED = "imagunity.directory.encryption-required";

    /**
     * Paquets chiffrés pour la box destinataire, avec les clés de chiffrement de son entrée d'annuaire. Le chiffrement
     * est obligatoire : une box encore réglée pour accepter les paquets en clair refuse de démarrer, plutôt que
     * d'ignorer ce réglage en silence.
     */
    @Bean
    PacketEncryption packetEncryption(LocalDirectory directory, BoxIdentity identity, Environment environment) {
        String required = environment.getProperty(ENCRYPTION_REQUIRED);
        if (required != null && !Boolean.parseBoolean(required.strip())) {
            throw new IllegalStateException("IMAGUNITY_DIRECTORY_ENCRYPTION_REQUIRED=" + required + " n'est plus "
                    + "possible : le chiffrement applicatif des paquets est obligatoire. Retirer ce réglage, et faire "
                    + "réémettre l'entrée d'annuaire des box qui ne publient pas de clés de chiffrement");
        }
        return new DirectoryPacketEncryption(directory, identity);
    }

    @Bean
    PeerAuthorization peerAuthorization(LocalDirectory directory, BoxIdentity identity) {
        return new DirectoryPeerAuthorization(directory, identity.boxId());
    }

    @Bean
    PeerHandshake peerHandshake(PeerRepository peers, TransportPort transport, BoxIdentity identity,
                                LocalDirectory directory, ExecutorService orchestrationExecutor, Clock clock,
                                OrchestrationProperties orchestration) {
        return new PeerHandshake(peers, transport, identity, directory, orchestrationExecutor, clock,
                orchestration.pairingTimeout());
    }

    @Bean
    DirectoryClient directoryClient(DirectoryProperties properties, BoxIdentity identity) {
        // Lectures signées par la box : elles passent la lecture réservée de l'annuaire une fois la box enrôlée.
        return new DirectoryClient(properties.url(), Duration.ofSeconds(10)).readAs(new com.imagunity.directory
                .SignedRead(identity.boxId().toString(), identity.keys()));
    }

    /**
     * Charge la vue locale de l'annuaire (entrées vérifiées gardées en base) avant le démarrage de l'orchestration :
     * les messages rejoués par le rattrapage du transport, ou arrivés dès l'abonnement, sont vérifiés avec elle. Sans
     * cela, un accusé arrivé pendant l'arrêt de la box serait refusé, faute d'entrée pour son émetteur.
     */
    @Bean
    SmartLifecycle directoryCacheLifecycle(LocalDirectory directory) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                directory.load();
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }

            @Override
            public int getPhase() {
                return CACHE_PHASE;
            }
        };
    }

    /**
     * Configuration centralisée : documents signés par le responsable, lus avec l'annuaire. Ses règles l'emportent
     * sur les règles locales de priorité et de routage, sans redémarrage.
     */
    @Bean
    @ConditionalOnProperty(name = "imagunity.central-configuration.enabled", havingValue = "true",
            matchIfMissing = true)
    CentralRules centralRules(BoxIdentity identity, KeyFiles.OrganizationPublicKey key, LocalDirectory directory,
                              CentralConfigurationProperties properties, Clock clock, ReloadablePriority priority,
                              org.springframework.beans.factory.ObjectProvider<com.imagunity.dicom.DicomStoreScp> scp) {
        CentralRules rules = new CentralRules(identity.boxId(), key.organization(), key.key(), properties.file(),
                clock, () -> directory.entry(identity.boxId()).map(com.imagunity.directory.BoxEntry::groups)
                .<java.util.Collection<String>>map(g -> g).orElse(java.util.List.of()));
        rules.load();
        priority.central(rules::priority);
        scp.ifAvailable(s -> s.centralRouting(rules::routing));
        return rules;
    }

    /**
     * Nœuds et routes DICOM de la configuration centralisée : entrée DICOM, choix des PACS de restitution, appairage
     * automatique des box désignées. Sans configuration centralisée (ou sans bloc DICOM), la configuration locale.
     */
    @Bean
    CentralDicom centralDicom(org.springframework.beans.factory.ObjectProvider<CentralRules> central,
                              com.imagunity.domain.peer.PeerRepository peers, LocalDirectory directory,
                              BoxIdentity identity, com.imagunity.orchestration.PairingService pairing,
                              com.imagunity.transport.TransportPort transport, RestoreProperties restore, Clock clock,
                              org.springframework.beans.factory.ObjectProvider<com.imagunity.dicom.DicomStoreScp> scp,
                              org.springframework.beans.factory.ObjectProvider<com.imagunity.domain.exam.StudyRestorer>
                                      restorer) {
        CentralRules rules = central.getIfAvailable();
        CentralDicom dicom = new CentralDicom(rules == null ? java.util.Optional::empty : rules::dicom, peers,
                directory, identity.boxId(), pairing, transport.addressKind(), restore.boxAet(), clock);
        scp.ifAvailable(s -> s.central(dicom::input));
        if (rules != null) {
            rules.nodeChecks(dicom::checks);
        }
        if (restorer.getIfAvailable() instanceof com.imagunity.dicom.DicomRestorer r) {
            r.router(dicom::router);
        }
        return dicom;
    }

    /** Envoi automatique de la demande d'enrôlement, tant que la box n'est pas dans l'annuaire. */
    @Bean
    AutomaticEnrollment automaticEnrollment(LocalDirectory directory, DirectoryClient client, BoxIdentity identity,
                                            TransportPort transport, com.imagunity.api.BoxProperties box,
                                            DirectoryProperties properties, Clock clock) {
        try {
            return new AutomaticEnrollment(directory, client, identity, transport, box.name(),
                    properties.enrollmentCode(), clock);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("IMAGUNITY_DIRECTORY_ENROLLMENT_CODE : " + e.getMessage(), e);
        }
    }

    /** Synchronisation périodique (la première au démarrage), puis nouvelle poignée de main avec les non-liés. */
    @Bean
    SmartLifecycle directoryLifecycle(LocalDirectory directory, DirectoryClient client, PeerHandshake handshake,
                                      DirectoryProperties properties, ExecutorService orchestrationExecutor,
                                      org.springframework.beans.factory.ObjectProvider<CentralRules> central,
                                      BoxIdentity identity, AutomaticEnrollment enrollment, CentralDicom dicom,
                                      KeyFiles.OrganizationPublicKey key, Clock clock) {
        return new SmartLifecycle() {
            private ScheduledExecutorService scheduler;
            private Thread wakeUp;

            @Override
            public void start() {
                handshake.start();
                ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().name("annuaire").daemon().factory());
                scheduler = s;
                CentralRules rules = central.getIfAvailable();
                TestRequests tests = new TestRequests(identity.boxId(), key.organization(), key.key(), clock);
                Runnable sync = () -> synchronizeAll(directory, client, handshake, orchestrationExecutor, rules,
                        identity, enrollment, dicom, tests);
                s.scheduleWithFixedDelay(sync, 0, properties.syncInterval().toMillis(), TimeUnit.MILLISECONDS);
                // Réveil par l'annuaire : une lecture tout de suite, une seule en attente à la fois.
                java.util.concurrent.atomic.AtomicBoolean pending = new java.util.concurrent.atomic.AtomicBoolean();
                Runnable wake = () -> {
                    if (pending.compareAndSet(false, true)) {
                        try {
                            s.execute(() -> {
                                pending.set(false);
                                sync.run();
                            });
                        } catch (java.util.concurrent.RejectedExecutionException e) {
                            pending.set(false); // box en cours d'arrêt
                        }
                    }
                };
                wakeUp = Thread.ofPlatform().name("annuaire-reveil").daemon()
                        .start(new DirectoryWakeUp(client, identity.boxId(), wake));
            }

            @Override
            public void stop() {
                wakeUp.interrupt();
                wakeUp = null;
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

    /**
     * Une lecture périodique de l'annuaire, puis de la configuration centrale. Une erreur imprévue est journalisée
     * sans sortir de la tâche : une exception non rattrapée arrêterait définitivement la planification (plus de
     * révocations ni de configurations reçues jusqu'au redémarrage de la box).
     */
    static void synchronizeAll(LocalDirectory directory, DirectoryClient client, PeerHandshake handshake,
                               java.util.concurrent.Executor executor, CentralRules rules, BoxIdentity identity,
                               AutomaticEnrollment enrollment) {
        synchronizeAll(directory, client, handshake, executor, rules, identity, enrollment, null);
    }

    static void synchronizeAll(LocalDirectory directory, DirectoryClient client, PeerHandshake handshake,
                               java.util.concurrent.Executor executor, CentralRules rules, BoxIdentity identity,
                               AutomaticEnrollment enrollment, CentralDicom dicom) {
        synchronizeAll(directory, client, handshake, executor, rules, identity, enrollment, dicom, null);
    }

    /**
     * Avec {@code tests}, une demande d'essai du responsable fait essayer tout de suite chaque destination, et le compte
     * rendu part aussitôt en la nommant.
     */
    static void synchronizeAll(LocalDirectory directory, DirectoryClient client, PeerHandshake handshake,
                               java.util.concurrent.Executor executor, CentralRules rules, BoxIdentity identity,
                               AutomaticEnrollment enrollment, CentralDicom dicom, TestRequests tests) {
        try {
            synchronize(directory, client, handshake, executor);
        } catch (RuntimeException e) {
            LOG.error("Lecture de l'annuaire en échec, nouvel essai au prochain intervalle", e);
        }
        if (enrollment != null) {
            try {
                enrollment.submitIfNeeded();
            } catch (RuntimeException e) {
                LOG.error("Envoi de la demande d'enrôlement en échec, nouvel essai au prochain intervalle", e);
            }
        }
        if (rules != null) {
            try {
                rules.synchronizeWith(client, identity.keys(), dicom == null ? () -> { } : () -> {
                    java.util.Optional<String> asked = tests == null ? java.util.Optional.empty() : tests.next(client);
                    dicom.checkDestinations(asked.isPresent());
                    asked.ifPresent(rules::answered);
                });
            } catch (RuntimeException e) {
                LOG.error("Lecture de la configuration centrale en échec, nouvel essai au prochain intervalle", e);
            }
        }
        if (dicom != null) {
            try {
                dicom.pairMissing();
            } catch (RuntimeException e) {
                LOG.error("Appairage automatique en échec, nouvel essai au prochain intervalle", e);
            }
        }
    }

    static void synchronize(LocalDirectory directory, DirectoryClient client, PeerHandshake handshake,
                            java.util.concurrent.Executor executor) {
        // Preuve de fraîcheur d'abord : plus ancienne que les entrées lues ensuite, elle n'en signale aucune à tort.
        if (directory.freshnessChecked()) {
            directory.synchronizeFreshness(client::fetchFreshness);
        }
        LocalDirectory.SyncStatus status = directory.synchronize(client::fetch);
        if (status.error() == null) {
            LOG.debug("Annuaire lu : {} entrée(s) retenue(s), {} écartée(s)", status.accepted(), status.rejected());
        }
        if (directory.federated()) {
            directory.synchronizeRecognitions(client::fetchRecognitions);
            for (com.imagunity.directory.Recognition r : directory.activeRecognitions()) {
                String partner = r.partner().fingerprint();
                directory.synchronizePartnerFreshness(partner, () -> client.fetchPartnerFreshness(partner));
                directory.synchronizePartner(partner, () -> client.fetchPartnerEntries(partner));
            }
        }
        if (directory.incomplete()) {
            // Lecture par différence en défaut d'après la preuve de fraîcheur : la prochaine lecture est complète.
            client.resetCursors();
        }
        executor.execute(handshake::greetUnbound);
    }
}
