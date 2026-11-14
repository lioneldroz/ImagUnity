package com.imagunity.directoryserver;

import com.imagunity.directory.EnrollmentSubmission;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Demandes d'enrôlement en attente, une par box. */
@Repository
public class EnrollmentStore {

    /** Demande en attente. */
    record Pending(UUID boxId, String displayName, String fingerprint, String codeId, String submission,
                   Instant receivedAt) {
    }

    private final JdbcClient jdbc;

    public EnrollmentStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    /** Enregistre la demande de cette box, à la place de la précédente. */
    void save(UUID boxId, String displayName, String fingerprint, EnrollmentSubmission submission, Instant at) {
        jdbc.sql("DELETE FROM enrollment_request WHERE box_id = ?").param(boxId).update();
        jdbc.sql("INSERT INTO enrollment_request (box_id, display_name, fingerprint, code_id, submission, "
                        + "received_at) VALUES (?, ?, ?, ?, ?, ?)")
                .params(boxId, displayName, fingerprint, submission.code(), submission.toJson(),
                        at.atOffset(ZoneOffset.UTC)).update();
    }

    boolean exists(UUID boxId) {
        return jdbc.sql("SELECT COUNT(*) FROM enrollment_request WHERE box_id = ?").param(boxId)
                .query(Integer.class).single() > 0;
    }

    int count() {
        return jdbc.sql("SELECT COUNT(*) FROM enrollment_request").query(Integer.class).single();
    }

    /** Demandes en attente, de la plus récente à la plus ancienne. */
    List<Pending> all() {
        return jdbc.sql("SELECT * FROM enrollment_request ORDER BY received_at DESC")
                .query((rs, n) -> new Pending(rs.getObject("box_id", UUID.class), rs.getString("display_name"),
                        rs.getString("fingerprint"), rs.getString("code_id"), rs.getString("submission"),
                        rs.getObject("received_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    void delete(UUID boxId) {
        jdbc.sql("DELETE FROM enrollment_request WHERE box_id = ?").param(boxId).update();
    }

    /** Efface les demandes reçues avant cette date ; rend leur nombre. */
    int purgeBefore(Instant limit) {
        return jdbc.sql("DELETE FROM enrollment_request WHERE received_at < ?").param(limit.atOffset(ZoneOffset.UTC))
                .update();
    }
}
