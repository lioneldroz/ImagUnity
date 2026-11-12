package com.imagunity.config;

import com.imagunity.api.ConsoleEvents;
import com.imagunity.api.ConsoleProperties;
import com.imagunity.api.NotifyingInboundRepository;
import com.imagunity.api.NotifyingTransferRepository;
import com.imagunity.dicom.DicomRestorer;
import com.imagunity.domain.exam.StudyRestorer;
import com.imagunity.domain.inbound.InboundRepository;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.domain.transfer.TransferRepository;
import com.imagunity.orchestration.MessageSigner;
import com.imagunity.orchestration.PairingService;
import com.imagunity.orchestration.PacketEncryption;
import com.imagunity.orchestration.PeerAuthorization;
import com.imagunity.orchestration.ReceiveOrchestrator;
import com.imagunity.orchestration.SendOrchestrator;
import com.imagunity.packaging.PackageReader;
import com.imagunity.packaging.StudyPackager;
import com.imagunity.persistence.JdbcInboundRepository;
import com.imagunity.persistence.JdbcPeerRepository;
import com.imagunity.persistence.JdbcTransferRepository;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Persistance (signalée aux consoles), orchestrateurs d'envoi et de réception, appairage. */
@Configuration(proxyBeanMethods = false)
class OrchestrationConfiguration {

    /** Démarre avant l'entrée DICOM (phase par défaut), qui peut lui signaler des études reprises. */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 100;

    private static final Logger LOG = LoggerFactory.getLogger(OrchestrationConfiguration.class);

    /** Dépôt SQL ; chaque changement est signalé aux consoles connectées. */
    @Bean
    TransferRepository transferRepository(DataSource dataSource, ConsoleEvents events, ConsoleProperties console,
                                          Clock clock) {
        return new NotifyingTransferRepository(new JdbcTransferRepository(dataSource), events, console, clock);
    }

    @Bean
    PeerRepository peerRepository(DataSource dataSource) {
        return new JdbcPeerRepository(dataSource);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Un seul fil : les étapes des transferts et les événements du transport sont traités dans l'ordre. */
    @Bean(destroyMethod = "shutdown")
    ExecutorService orchestrationExecutor() {
        return Executors.newSingleThreadExecutor(Thread.ofPlatform().name("orchestrateur").daemon().factory());
    }

    @Bean
    SendOrchestrator sendOrchestrator(TransferRepository transfers, PeerRepository peers, TransportPort transport,
                                      OrchestrationProperties properties, ExecutorService orchestrationExecutor,
                                      Clock clock, ObjectProvider<PeerAuthorization> authorization,
                                      ObjectProvider<MessageSigner> signer,
                                      ObjectProvider<PacketEncryption> encryption) {
        return new SendOrchestrator(transfers, peers, new StudyPackager(properties.packagingConfig()), transport,
                properties.outboxDir(), orchestrationExecutor, clock,
                authorization.getIfAvailable(PeerAuthorization::allowAll), signer.getIfAvailable(),
                encryption.getIfAvailable(PacketEncryption::none));
    }

    @Bean
    InboundRepository inboundRepository(DataSource dataSource, ConsoleEvents events) {
        return new NotifyingInboundRepository(new JdbcInboundRepository(dataSource), events);
    }

    @Bean
    StudyRestorer studyRestorer(RestoreProperties properties) {
        return new DicomRestorer(properties.restoreConfig());
    }

    @Bean
    ReceiveOrchestrator receiveOrchestrator(InboundRepository inbound, PeerRepository peers, TransportPort transport,
                                            StudyRestorer restorer, RestoreProperties properties,
                                            ExecutorService orchestrationExecutor, Clock clock,
                                            ObjectProvider<PeerAuthorization> authorization,
                                            ObjectProvider<MessageSigner> signer,
                                            ObjectProvider<PacketEncryption> encryption) {
        return new ReceiveOrchestrator(inbound, peers, transport, new PackageReader(properties.maxUnpackedSize().toBytes()),
                restorer, properties.inboxDir(), properties.maxInboundFileSize().toBytes(), orchestrationExecutor,
                clock, authorization.getIfAvailable(PeerAuthorization::allowAll), signer.getIfAvailable(),
                encryption.getIfAvailable(PacketEncryption::none));
    }

    @Bean
    PairingService pairingService(PeerRepository peers, TransportPort transport, OrchestrationProperties properties,
                                  Clock clock) {
        return new PairingService(peers, transport, clock, properties.pairingTimeout());
    }

    /**
     * Démarre l'appairage et les orchestrateurs, puis rattrape les arrivées que le client de transport a reçues
     * pendant que la box était arrêtée (sans quoi un fichier proposé à ce moment ne serait jamais accepté).
     */
    @Bean
    SmartLifecycle orchestrationLifecycle(SendOrchestrator orchestrator, ReceiveOrchestrator receiver,
                                          PairingService pairing, TransportPort transport) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                pairing.start();
                orchestrator.start();
                receiver.start();
                try {
                    transport.catchUp();
                } catch (TransportException e) {
                    LOG.warn("Rattrapage des arrivées impossible : {}", e.getMessage());
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
                return PHASE;
            }
        };
    }
}
