package com.imagunity.persistence;

import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferPart;
import com.imagunity.domain.transfer.TransferRepository;
import com.imagunity.domain.transfer.TransferState;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link TransferRepository} en SQL (H2 dans la box). */
public final class JdbcTransferRepository implements TransferRepository {

    private static final Set<TransferState> TERMINAL = Set.of(TransferState.ACKNOWLEDGED, TransferState.FAILED);
    private static final String COLUMNS = "id, study_uid, called_aet, destination, state, size_bytes, "
            + "package_group_id, part_count, error_message, created_at, updated_at";

    private final JdbcClient jdbc;

    public JdbcTransferRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public void create(Transfer t) {
        jdbc.sql("INSERT INTO transfer (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(t.id(), t.studyInstanceUid(), t.calledAet(), t.destination(), t.state().name(), t.sizeBytes(),
                        t.packageGroupId(), t.partCount(), t.errorMessage(), utc(t.createdAt()), utc(t.updatedAt()))
                .update();
    }

    @Override
    public void update(Transfer t) {
        int updated = jdbc.sql("UPDATE transfer SET state = ?, package_group_id = ?, part_count = ?, "
                        + "error_message = ?, updated_at = ? WHERE id = ?")
                .params(t.state().name(), t.packageGroupId(), t.partCount(), t.errorMessage(), utc(t.updatedAt()),
                        t.id())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Transfert inconnu : " + t.id());
        }
    }

    @Override
    public Optional<Transfer> find(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfer WHERE id = ?").param(id)
                .query(JdbcTransferRepository::transfer).optional();
    }

    @Override
    public Optional<Transfer> findActive(String studyInstanceUid, String calledAet) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfer WHERE study_uid = ? AND called_aet = ? "
                        + "AND state NOT IN (" + placeholders(TERMINAL.size()) + ") ORDER BY created_at DESC")
                .params(studyInstanceUid, calledAet)
                .params(TERMINAL.stream().map(Enum::name).toList())
                .query(JdbcTransferRepository::transfer).list().stream().findFirst();
    }

    @Override
    public List<Transfer> findByStates(Set<TransferState> states) {
        if (states.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfer WHERE state IN (" + placeholders(states.size())
                        + ") ORDER BY created_at")
                .params(states.stream().map(Enum::name).toList())
                .query(JdbcTransferRepository::transfer).list();
    }

    @Override
    public List<Transfer> findRecent(int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM transfer ORDER BY updated_at DESC, created_at DESC LIMIT ?")
                .param(limit).query(JdbcTransferRepository::transfer).list();
    }

    @Override
    public Map<TransferState, Long> countByState() {
        Map<TransferState, Long> counts = new EnumMap<>(TransferState.class);
        jdbc.sql("SELECT state, COUNT(*) AS n FROM transfer GROUP BY state")
                .query(rs -> {
                    counts.put(TransferState.valueOf(rs.getString("state")), rs.getLong("n"));
                });
        return counts;
    }

    @Override
    public void saveParts(UUID transferId, List<TransferPart> parts) {
        jdbc.sql("DELETE FROM transfer_part WHERE transfer_id = ?").param(transferId).update();
        for (TransferPart p : parts) {
            jdbc.sql("INSERT INTO transfer_part (transfer_id, part_index, part_count, file_path, size_bytes, sha256, "
                            + "transport_file_id, uploaded) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(transferId, p.index(), p.count(), p.file().toString(), p.size(), p.sha256(),
                            p.transportFileId(), p.uploaded())
                    .update();
        }
    }

    @Override
    public void updatePart(TransferPart p) {
        int updated = jdbc.sql("UPDATE transfer_part SET file_path = ?, size_bytes = ?, sha256 = ?, "
                        + "transport_file_id = ?, uploaded = ? WHERE transfer_id = ? AND part_index = ?")
                .params(p.file().toString(), p.size(), p.sha256(), p.transportFileId(), p.uploaded(), p.transferId(),
                        p.index())
                .update();
        if (updated != 1) {
            throw new IllegalStateException("Paquet inconnu : " + p.transferId() + " #" + p.index());
        }
    }

    @Override
    public List<TransferPart> parts(UUID transferId) {
        return jdbc.sql("SELECT * FROM transfer_part WHERE transfer_id = ? ORDER BY part_index").param(transferId)
                .query(JdbcTransferRepository::part).list();
    }

    @Override
    public Optional<TransferPart> findPartByTransportFileId(long transportFileId) {
        return jdbc.sql("SELECT * FROM transfer_part WHERE transport_file_id = ?").param(transportFileId)
                .query(JdbcTransferRepository::part).optional();
    }

    private static Transfer transfer(ResultSet rs, int row) throws SQLException {
        return new Transfer(rs.getObject("id", UUID.class), rs.getString("study_uid"), rs.getString("called_aet"),
                rs.getString("destination"), TransferState.valueOf(rs.getString("state")), rs.getLong("size_bytes"),
                rs.getString("package_group_id"), rs.getInt("part_count"), rs.getString("error_message"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static TransferPart part(ResultSet rs, int row) throws SQLException {
        return new TransferPart(rs.getObject("transfer_id", UUID.class), rs.getInt("part_index"),
                rs.getInt("part_count"), Path.of(rs.getString("file_path")), rs.getLong("size_bytes"),
                rs.getString("sha256"), rs.getObject("transport_file_id", Long.class), rs.getBoolean("uploaded"));
    }

    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static String placeholders(int n) {
        return String.join(", ", java.util.Collections.nCopies(n, "?"));
    }
}
