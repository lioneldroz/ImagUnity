package com.imagunity.directoryserver;

import com.imagunity.directory.ConfigurationReport;
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

/** Documents de configuration publiés et derniers comptes rendus des box. */
@Repository
public class ConfigurationStore {

    private final JdbcClient jdbc;

    public ConfigurationStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    OptionalLong highestSequence() {
        long highest = jdbc.sql("SELECT COALESCE(MAX(sequence), 0) FROM configuration_document").query(Long.class)
                .single();
        return highest == 0 ? OptionalLong.empty() : OptionalLong.of(highest);
    }

    Optional<SignedDocument> find(long sequence) {
        return jdbc.sql("SELECT document FROM configuration_document WHERE sequence = ?").param(sequence)
                .query(String.class).optional().map(SignedDocument::fromJson);
    }

    void save(long sequence, SignedDocument document, Instant issuedAt, Instant publishedAt) {
        jdbc.sql("INSERT INTO configuration_document (sequence, document, issued_at, published_at) VALUES (?, ?, ?, ?)")
                .params(sequence, document.toJson(), utc(issuedAt), utc(publishedAt)).update();
    }

    /** Documents publiés, du plus ancien au plus récent. */
    List<SignedDocument> all() {
        return jdbc.sql("SELECT document FROM configuration_document ORDER BY sequence").query(String.class).list()
                .stream().map(SignedDocument::fromJson).toList();
    }

    /** Enregistre un compte rendu s'il est plus récent que le dernier reçu de cette box. */
    boolean saveReport(ConfigurationReport report, Instant receivedAt) {
        Optional<OffsetDateTime> last = jdbc.sql("SELECT reported_at FROM configuration_report WHERE box_id = ?")
                .param(report.boxId()).query(OffsetDateTime.class).optional();
        if (last.isPresent() && !report.reportedAt().isAfter(last.get().toInstant())) {
            return false;
        }
        jdbc.sql("DELETE FROM configuration_report WHERE box_id = ?").param(report.boxId()).update();
        jdbc.sql("INSERT INTO configuration_report (box_id, applied, applied_at, refused, refusal, reported_at, "
                        + "received_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(report.boxId(), report.applied(), report.appliedAt() == null ? null : utc(report.appliedAt()),
                        report.refused(), report.refusal(), utc(report.reportedAt()), utc(receivedAt)).update();
        return true;
    }

    /** Dernier compte rendu de chaque box. */
    List<Report> reports() {
        return jdbc.sql("SELECT box_id, applied, applied_at, refused, refusal, reported_at FROM configuration_report")
                .query((rs, n) -> new Report(rs.getObject("box_id", UUID.class), rs.getLong("applied"),
                        instant(rs.getObject("applied_at", OffsetDateTime.class)), rs.getLong("refused"),
                        rs.getString("refusal"), instant(rs.getObject("reported_at", OffsetDateTime.class))))
                .list();
    }

    /** Compte rendu enregistré. */
    record Report(UUID boxId, long applied, Instant appliedAt, long refused, String refusal, Instant reportedAt) {
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
