package com.imagunity.config;

import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.transport.DisabledTransport;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import com.imagunity.transport.iu.IuDirectory;
import com.imagunity.transport.iu.IuTransport;
import com.imagunity.transport.iu.Retry;
import com.imagunity.transport.simplex.SimplexChatClient;
import com.imagunity.transport.simplex.SimplexTransport;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Transport entre box : IU-T (relais de l'organisation), client simplex-chat, ou transport désactivé. */
@Configuration(proxyBeanMethods = false)
class TransportConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TransportConfiguration.class);
    private static final Duration RETRY_PAUSE = Duration.ofSeconds(2);

    /** Attente entre deux tentatives ; remplaçable pour les tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    @Bean(destroyMethod = "close")
    TransportPort transport(TransportProperties properties, IuTransportProperties iu,
                            ObjectProvider<BoxIdentity> identity, ObjectProvider<LocalDirectory> directory,
                            Clock clock) {
        if (iu.enabled()) {
            return iuTransport(iu, identity.getIfAvailable(), directory.getIfAvailable(), clock);
        }
        if (!properties.enabled()) {
            LOG.warn("Transport désactivé : les transferts passeront en échec");
            return new DisabledTransport();
        }
        LOG.info("Connexion au client simplex-chat sur {}", properties.url());
        return connectWithRetry(
                () -> new SimplexTransport(SimplexChatClient.connect(properties.url(), properties.commandTimeout())),
                properties.startupTimeout(), RETRY_PAUSE, Thread::sleep);
    }

    static IuTransport iuTransport(IuTransportProperties iu, BoxIdentity identity, LocalDirectory directory,
                                   Clock clock) {
        if (identity == null || directory == null) {
            throw new IllegalStateException("Transport IU-T sans annuaire : l'activer (IMAGUNITY_DIRECTORY_ENABLED=true)"
                    + ", l'appairage, les clés et les adresses de file en viennent");
        }
        if (iu.relay() == null) {
            throw new IllegalStateException("Transport IU-T sans relais (IMAGUNITY_IU_RELAY)");
        }
        LOG.info("Transport IU-T par le relais {}", iu.relay());
        return new IuTransport(new IuTransport.Settings(iu.relay(), iu.stateDir(), iu.ttl(), iu.parallel(),
                iu.keepState()), identity.boxId(), identity.keys(), identity.encryption(),
                IuDirectory.of(directory, clock), HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .build(), clock, Retry.standard());
    }

    /**
     * Démarre IU-T (relève de la file, boîte d'envoi, reprise des transferts) une fois tous les écouteurs abonnés, y
     * compris la poignée de main avec l'annuaire : sans quoi un message arrivé tout de suite serait acquitté sans
     * avoir été vu.
     */
    @Bean
    SmartLifecycle iuTransportLifecycle(TransportPort transport) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                if (transport instanceof IuTransport iu) {
                    iu.start();
                }
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
                return DirectoryConfiguration.PHASE + 1;
            }
        };
    }

    /**
     * Bascule vers IU-T : au premier démarrage, les partenaires appairés par SimpleX et liés à une box de l'annuaire
     * gardent leur nom (et donc les routes DICOM qui les désignent) et reçoivent leur contact IU-T ; les contacts
     * jamais vérifiés sont oubliés. Avant l'orchestration, qui reprend les transferts au démarrage.
     */
    @Bean
    SmartLifecycle iuPeerMigration(TransportPort transport, PeerRepository peers) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                if (transport instanceof IuTransport iu && !iu.peersMigrated()) {
                    migratePeers(iu, peers);
                }
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
                return OrchestrationConfiguration.PHASE - 1;
            }
        };
    }

    static void migratePeers(IuTransport iu, PeerRepository peers) {
        for (Peer peer : peers.findAll()) {
            if (peer.boxId() == null) {
                peers.delete(peer.name());
                LOG.info("Bascule IU-T : contact {} jamais vérifié par l'annuaire, oublié", peer.name());
                continue;
            }
            long contact = iu.adopt(peer.boxId());
            peers.save(new Peer(peer.name(), contact, peer.displayName(), peer.pairedAt(), peer.boxId()));
            LOG.info("Bascule IU-T : partenaire {} (box {}) repris", peer.name(), peer.boxId());
        }
        iu.markPeersMigrated();
    }

    /**
     * Répète la tentative jusqu'au succès ou jusqu'à l'expiration du délai : dans un conteneur,
     * simplex-chat peut ouvrir son port après le démarrage de la box.
     */
    static <T> T connectWithRetry(Supplier<T> attempt, Duration timeout, Duration pause, Sleeper sleeper) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            try {
                return attempt.get();
            } catch (TransportException e) {
                if (System.nanoTime() + pause.toNanos() > deadline) {
                    throw new TransportException("Client simplex-chat injoignable après " + timeout, e);
                }
                LOG.info("Client simplex-chat pas encore prêt ({}), nouvelle tentative", e.getMessage());
                try {
                    sleeper.sleep(pause);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new TransportException("Attente du client simplex-chat interrompue", ie);
                }
            }
        }
    }
}
