package com.imagunity.relay;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Bail en base : une tâche périodique (la purge) ne tourne que sur une instance du relais à la fois. Le bail est pris
 * pour une durée, renouvelé par son détenteur à chaque passage, et repris par une autre instance s'il n'est plus
 * renouvelé (instance arrêtée).
 */
public final class RelayLease {

    private final JdbcClient jdbc;
    private final Clock clock;
    private final String holder;

    /** @param holder identifiant de cette instance */
    public RelayLease(DataSource dataSource, Clock clock, String holder) {
        this.jdbc = JdbcClient.create(dataSource);
        this.clock = clock;
        this.holder = holder;
    }

    public String holder() {
        return holder;
    }

    /** Prend ou renouvelle le bail {@code name} ; faux s'il est détenu par une autre instance. */
    public boolean acquire(String name, Duration duration) {
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        var until = now.plus(duration);
        if (jdbc.sql("UPDATE relay_lease SET holder = ?, until = ? WHERE name = ? AND (holder = ? OR until < ?)")
                .params(holder, until, name, holder, now).update() == 1) {
            return true;
        }
        try {
            jdbc.sql("INSERT INTO relay_lease (name, holder, until) VALUES (?, ?, ?)").params(name, holder, until)
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false; // détenu, et encore valable, par une autre instance
        }
    }
}
