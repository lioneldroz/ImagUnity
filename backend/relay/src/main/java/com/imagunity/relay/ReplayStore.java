package com.imagunity.relay;

import com.imagunity.protocol.ReplayGuard;
import com.imagunity.protocol.SignedRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Anti-rejeu partagé par toutes les instances du relais (spécification § 6.3, § 6.8) : mêmes règles que
 * {@link ReplayGuard} (horodatage dans la fenêtre, identifiant jamais vu dans la fenêtre), mais les identifiants vus
 * sont en base. L'insertion échoue si l'identifiant existe déjà, quelle que soit l'instance qui l'a vu : le refus ne
 * dépend pas du chemin pris par la requête rejouée.
 */
public final class ReplayStore {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration window;

    public ReplayStore(DataSource dataSource, Clock clock) {
        this(dataSource, clock, ReplayGuard.WINDOW);
    }

    ReplayStore(DataSource dataSource, Clock clock, Duration window) {
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = clock;
        this.window = window;
    }

    /**
     * Enregistre la requête si elle est admise.
     *
     * @return la raison du refus, ou vide si la requête est admise
     */
    public Optional<String> admit(SignedRequest request) {
        Optional<String> outside = outsideWindow(request);
        if (outside.isPresent()) {
            return outside;
        }
        long now = clock.instant().getEpochSecond();
        // Gardé jusqu'à ce que son horodatage sorte de la fenêtre.
        Instant expires = Instant.ofEpochSecond(Math.max(now, request.timestamp()) + window.toSeconds());
        try {
            jdbc.sql("INSERT INTO request_seen (request_id, expires_at) VALUES (?, ?)")
                    .params(request.requestId(), expires.atOffset(ZoneOffset.UTC)).update();
            return Optional.empty();
        } catch (DuplicateKeyException e) {
            return Optional.of("requête rejouée");
        }
    }

    /**
     * Contrôle de l'horodatage seul, sans rien enregistrer : à faire avant de vérifier la signature (coûteuse), alors
     * que l'enregistrement de l'identifiant ({@link #admit}) se fait après, pour qu'une requête non signée n'écrive
     * rien en base.
     */
    public Optional<String> outsideWindow(SignedRequest request) {
        if (Math.abs(clock.instant().getEpochSecond() - request.timestamp()) > window.toSeconds()) {
            return Optional.of("horodatage hors de la fenêtre admise (horloge de la box à vérifier)");
        }
        return Optional.empty();
    }

    /** Oublie les identifiants sortis de la fenêtre. */
    public int purge() {
        return jdbc.sql("DELETE FROM request_seen WHERE expires_at < ?")
                .param(clock.instant().atOffset(ZoneOffset.UTC)).update();
    }
}
