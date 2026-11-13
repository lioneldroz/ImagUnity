package com.imagunity.config;

import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.transport.DisabledTransport;
import com.imagunity.transport.TransportPort;
import com.imagunity.transport.iu.IuDirectory;
import com.imagunity.transport.iu.IuTransport;
import com.imagunity.transport.iu.Retry;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Transport entre box : IU-T, par le relais de l'organisation ; désactivé sans relais configuré. */
@Configuration(proxyBeanMethods = false)
class TransportConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(TransportConfiguration.class);

    @Bean(destroyMethod = "close")
    TransportPort transport(IuTransportProperties iu, ObjectProvider<BoxIdentity> identity,
                            ObjectProvider<LocalDirectory> directory, Clock clock) {
        if (iu.allRelays().isEmpty()) {
            LOG.warn("Transport désactivé, aucun relais IU-T configuré (IMAGUNITY_IU_RELAY) : les transferts "
                    + "passeront en échec");
            return new DisabledTransport();
        }
        return iuTransport(iu, identity.getIfAvailable(), directory.getIfAvailable(), clock);
    }

    static IuTransport iuTransport(IuTransportProperties iu, BoxIdentity identity, LocalDirectory directory,
                                   Clock clock) {
        if (identity == null || directory == null) {
            throw new IllegalStateException("Transport IU-T sans annuaire : l'activer (IMAGUNITY_DIRECTORY_ENABLED=true)"
                    + ", l'appairage, les clés et les adresses de file en viennent");
        }
        IuTransport.Settings settings = new IuTransport.Settings(iu.allRelays(), iu.stateDir(), iu.ttl(),
                iu.parallel(), iu.keepState(), iu.early(), iu.chunkSizeBytes(), iu.replicas());
        LOG.info("Transport IU-T par {} : {}", settings.relays().size() == 1 ? "le relais" : "les relais",
                settings.relays());
        return new IuTransport(settings, identity.boxId(), identity.keys(), identity.encryption(),
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
}
