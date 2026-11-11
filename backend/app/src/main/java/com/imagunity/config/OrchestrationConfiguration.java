package com.imagunity.config;

import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.domain.transfer.TransferRepository;
import com.imagunity.orchestration.PairingService;
import com.imagunity.orchestration.SendOrchestrator;
import com.imagunity.packaging.StudyPackager;
import com.imagunity.persistence.JdbcPeerRepository;
import com.imagunity.persistence.JdbcTransferRepository;
import com.imagunity.transport.TransportPort;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Persistance, orchestrateur des transferts et appairage. */
@Configuration(proxyBeanMethods = false)
class OrchestrationConfiguration {

    /** Démarre avant l'entrée DICOM (phase par défaut), qui peut lui signaler des études reprises. */
    static final int PHASE = SmartLifecycle.DEFAULT_PHASE - 100;

    @Bean
    TransferRepository transferRepository(DataSource dataSource) {
        return new JdbcTransferRepository(dataSource);
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
                                      Clock clock) {
        return new SendOrchestrator(transfers, peers, new StudyPackager(properties.packagingConfig()), transport,
                properties.outboxDir(), orchestrationExecutor, clock);
    }

    @Bean
    PairingService pairingService(PeerRepository peers, TransportPort transport, OrchestrationProperties properties,
                                  Clock clock) {
        return new PairingService(peers, transport, clock, properties.pairingTimeout());
    }

    @Bean
    SmartLifecycle orchestrationLifecycle(SendOrchestrator orchestrator, PairingService pairing) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                pairing.start();
                orchestrator.start();
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
