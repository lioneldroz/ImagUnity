package com.imagunity.relay;

import com.imagunity.directory.EntryCache;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Annuaire vu du relais : entrées déjà vérifiées et numéros d'émission vus (refus des retours en arrière), en base,
 * pour fonctionner même si l'annuaire est momentanément injoignable.
 */
public final class RelayDirectoryStore implements SerialMemory, EntryCache {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public RelayDirectoryStore(DataSource dataSource, TransactionTemplate transactions) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = transactions;
    }

    @Override
    public OptionalLong highest(UUID boxId) {
        return jdbc.sql("SELECT serial FROM directory_serial WHERE box_id = ?").param(boxId).query(Long.class)
                .optional().map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    @Override
    public void record(UUID boxId, long serial) {
        // Plusieurs instances partagent la base : insertion sans conflit, puis jamais de numéro plus petit.
        transactions.executeWithoutResult(status -> {
            jdbc.sql("INSERT INTO directory_serial (box_id, serial) VALUES (?, ?) ON CONFLICT DO NOTHING")
                    .params(boxId, serial).update();
            jdbc.sql("UPDATE directory_serial SET serial = GREATEST(serial, ?) WHERE box_id = ?").params(serial, boxId)
                    .update();
        });
    }

    @Override
    public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
        OffsetDateTime at = fetchedAt.atOffset(ZoneOffset.UTC);
        // Une instance en retard ne remplace jamais une entrée par une émission plus ancienne.
        transactions.executeWithoutResult(status -> {
            if (jdbc.sql("INSERT INTO directory_entry (box_id, serial, document, fetched_at) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT DO NOTHING").params(boxId, serial, document.toJson(), at).update() == 0) {
                jdbc.sql("UPDATE directory_entry SET serial = ?, document = ?, fetched_at = ? WHERE box_id = ? "
                        + "AND serial <= ?").params(serial, document.toJson(), at, boxId, serial).update();
            }
        });
    }

    @Override
    public List<SignedDocument> entries() {
        return jdbc.sql("SELECT document FROM directory_entry ORDER BY box_id").query(String.class).list().stream()
                .map(SignedDocument::fromJson).toList();
    }
}
