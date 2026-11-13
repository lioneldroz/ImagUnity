package com.imagunity.config;

import com.imagunity.dicom.DicomStoreScp;
import com.imagunity.dicom.StowService;
import com.imagunity.dicom.StudyListener;
import com.imagunity.orchestration.SendOrchestrator;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Branche l'entrée DICOM dans le cycle de vie de l'application. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "imagunity.dicom", name = "enabled", havingValue = "true", matchIfMissing = true)
class DicomInputConfiguration {

    /** Les études complètes sont confiées à l'orchestrateur (empaquetage, envoi, suivi d'état). */
    @Bean
    StudyListener studyListener(SendOrchestrator orchestrator) {
        return orchestrator::submit;
    }

    @Bean
    DicomStoreScp dicomStoreScp(DicomProperties properties, DicomLimitsProperties limits, StudyListener listener) {
        DicomStoreScp scp = new DicomStoreScp(properties.toConfig(), listener);
        scp.limits(limits.toLimits());
        return scp;
    }

    /** Réception STOW-RS (DICOMweb) : mêmes contrôles et même routage que le C-STORE. */
    @Bean
    StowService stowService(DicomStoreScp scp) {
        return new StowService(scp);
    }

    @Bean
    SmartLifecycle dicomInputLifecycle(DicomStoreScp scp) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                try {
                    scp.start();
                } catch (IOException e) {
                    throw new UncheckedIOException("Impossible d'ouvrir l'entrée DICOM", e);
                }
                running = true;
            }

            @Override
            public void stop() {
                scp.stop();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }
}
