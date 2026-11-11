package com.imagunity.config;

import com.imagunity.transport.DisabledTransport;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import com.imagunity.transport.simplex.SimplexChatClient;
import com.imagunity.transport.simplex.SimplexTransport;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Transport entre box : client simplex-chat, ou transport désactivé. */
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
    TransportPort transport(TransportProperties properties) {
        if (!properties.enabled()) {
            LOG.warn("Transport désactivé : les transferts passeront en échec");
            return new DisabledTransport();
        }
        LOG.info("Connexion au client simplex-chat sur {}", properties.url());
        return connectWithRetry(
                () -> new SimplexTransport(SimplexChatClient.connect(properties.url(), properties.commandTimeout())),
                properties.startupTimeout(), RETRY_PAUSE, Thread::sleep);
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
