package com.imagunity.directoryserver;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.SignedDocument;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Entrées publiées, historique compris. Pour chaque box, l'entrée courante est celle de numéro le plus haut. */
@Repository
public class EntryStore {

    private final JdbcClient jdbc;

    public EntryStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    void save(BoxEntry entry, SignedDocument document, Instant publishedAt) {
        jdbc.sql("INSERT INTO box_entry (box_id, serial, display_name, revoked, valid_until, document, published_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(entry.boxId(), entry.serial(), entry.displayName(), entry.revoked(), utc(entry.validUntil()),
                        document.toJson(), utc(publishedAt))
                .update();
    }

    OptionalLong highestSerial(UUID boxId) {
        return jdbc.sql("SELECT serial FROM box_entry WHERE box_id = ? ORDER BY serial DESC LIMIT 1").param(boxId)
                .query(Long.class).optional().map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    Optional<SignedDocument> find(UUID boxId, long serial) {
        return jdbc.sql("SELECT document FROM box_entry WHERE box_id = ? AND serial = ?").params(boxId, serial)
                .query(String.class).optional().map(SignedDocument::fromJson);
    }

    /** Entrée courante de chaque box, par nom. */
    List<SignedDocument> current() {
        return jdbc.sql("SELECT e.document FROM box_entry e WHERE e.serial = "
                        + "(SELECT MAX(serial) FROM box_entry m WHERE m.box_id = e.box_id) ORDER BY e.display_name, e.box_id")
                .query(String.class).list().stream().map(SignedDocument::fromJson).toList();
    }

    Optional<SignedDocument> current(UUID boxId) {
        return highestSerial(boxId).stream().boxed().findFirst().flatMap(serial -> find(boxId, serial));
    }

    /** Toutes les entrées d'une box, par numéro croissant. */
    List<SignedDocument> history(UUID boxId) {
        return jdbc.sql("SELECT document FROM box_entry WHERE box_id = ? ORDER BY serial").param(boxId)
                .query(String.class).list().stream().map(SignedDocument::fromJson).toList();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
