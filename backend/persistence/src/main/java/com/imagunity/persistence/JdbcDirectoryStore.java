package com.imagunity.persistence;

import com.imagunity.directory.EntryCache;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Côté box : cache des entrées de l'annuaire déjà vérifiées et mémoire des numéros d'émission vus (refus des
 * retours en arrière), en base, pour survivre aux redémarrages et à un annuaire momentanément injoignable.
 */
public final class JdbcDirectoryStore implements SerialMemory, EntryCache {

    private final JdbcClient jdbc;

    public JdbcDirectoryStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public OptionalLong highest(UUID boxId) {
        return jdbc.sql("SELECT serial FROM directory_serial WHERE box_id = ?").param(boxId).query(Long.class)
                .optional().map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    @Override
    public void record(UUID boxId, long serial) {
        jdbc.sql("MERGE INTO directory_serial (box_id, serial) KEY (box_id) VALUES (?, "
                        + "GREATEST(?, COALESCE((SELECT serial FROM directory_serial WHERE box_id = ?), 0)))")
                .params(boxId, serial, boxId).update();
    }

    @Override
    public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
        jdbc.sql("MERGE INTO directory_entry (box_id, serial, document, fetched_at) KEY (box_id) VALUES (?, ?, ?, ?)")
                .params(boxId, serial, document.toJson(), JdbcTransferRepository.utc(fetchedAt)).update();
    }

    @Override
    public List<SignedDocument> entries() {
        return jdbc.sql("SELECT document FROM directory_entry ORDER BY box_id").query(String.class).list().stream()
                .map(SignedDocument::fromJson).toList();
    }
}
