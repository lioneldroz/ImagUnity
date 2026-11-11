package com.imagunity.config;

import com.imagunity.dicom.DicomStoreScp;
import com.imagunity.dicom.StudyListener;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Branche l'entrée DICOM dans le cycle de vie de l'application. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "imagunity.dicom", name = "enabled", havingValue = "true", matchIfMissing = true)
class DicomInputConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DicomInputConfiguration.class);

    /**
     * Consommateur provisoire des études reçues : il se contente de les journaliser.
     * L'orchestrateur de la phase 4 le remplacera (empaquetage, envoi, suivi d'état).
     */
    @Bean
    @ConditionalOnMissingBean
    StudyListener loggingStudyListener() {
        return study -> LOG.info("Étude prête pour {} (en attente de l'orchestrateur, phase 4) : {} instance(s), "
                        + "{} série(s), {} octets", study.destination(), study.instances().size(),
                study.seriesCount(), study.totalSize());
    }

    @Bean
    DicomStoreScp dicomStoreScp(DicomProperties properties, StudyListener listener) {
        return new DicomStoreScp(properties.toConfig(), listener);
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
