package com.imagunity.directoryserver;

import com.imagunity.directory.SignedDocument;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Dernière demande d'essai de connexion de chaque box. */
@Repository
public class TestRequestStore {

    private final JdbcClient jdbc;

    public TestRequestStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Remplace la demande de cette box. */
    void save(UUID boxId, SignedDocument document, Instant requestedAt, Instant receivedAt) {
        jdbc.sql("DELETE FROM test_request WHERE box_id = ?").param(boxId).update();
        jdbc.sql("INSERT INTO test_request (box_id, document, requested_at, received_at) VALUES (?, ?, ?, ?)")
                .params(boxId, document.toJson(), utc(requestedAt), utc(receivedAt)).update();
    }

    Optional<SignedDocument> find(UUID boxId) {
        return jdbc.sql("SELECT document FROM test_request WHERE box_id = ?").param(boxId).query(String.class)
                .optional().map(SignedDocument::fromJson);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
