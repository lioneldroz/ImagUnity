package com.imagunity.persistence;

import com.imagunity.domain.inbound.InboundFile;
import com.imagunity.domain.inbound.InboundRepository;
import com.imagunity.domain.inbound.InboundTransfer;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link InboundRepository} en SQL (H2 dans la box). */
public final class JdbcInboundRepository implements InboundRepository {

    private final JdbcClient jdbc;

    public JdbcInboundRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public void saveFile(InboundFile f) {
        jdbc.sql("MERGE INTO inbound_file (transport_file_id, peer_name, file_name, size_bytes, state, file_path, "
                        + "updated_at) KEY (transport_file_id) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(f.transportFileId(), f.peerName(), f.fileName(), f.size(), f.state().name(),
                        f.file() == null ? null : f.file().toString(), JdbcTransferRepository.utc(f.updatedAt()))
                .update();
    }

    @Override
    public Optional<InboundFile> findFile(long transportFileId) {
        return jdbc.sql("SELECT * FROM inbound_file WHERE transport_file_id = ?").param(transportFileId)
                .query(JdbcInboundRepository::file).optional();
    }

    @Override
    public List<InboundFile> findFiles(String peerName, String fileName) {
        return jdbc.sql("SELECT * FROM inbound_file WHERE peer_name = ? AND file_name = ? "
                        + "ORDER BY updated_at DESC, transport_file_id DESC")
                .params(peerName, fileName).query(JdbcInboundRepository::file).list();
    }

    @Override
    public void saveTransfer(InboundTransfer t) {
        jdbc.sql("MERGE INTO inbound_transfer (transfer_id, peer_name, package_group_id, study_uid, part_count, "
                        + "announcement, state, error_message, created_at, updated_at) KEY (peer_name, transfer_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(t.transferId(), t.peerName(), t.packageGroupId(), t.studyInstanceUid(), t.partCount(),
                        t.announcement(), t.state().name(), t.errorMessage(), JdbcTransferRepository.utc(t.createdAt()),
                        JdbcTransferRepository.utc(t.updatedAt()))
                .update();
    }

    @Override
    public Optional<InboundTransfer> findTransfer(String peerName, UUID transferId) {
        return jdbc.sql("SELECT * FROM inbound_transfer WHERE peer_name = ? AND transfer_id = ?")
                .params(peerName, transferId).query(JdbcInboundRepository::transfer).optional();
    }

    @Override
    public Optional<InboundTransfer> findTransferByGroup(String peerName, String packageGroupId) {
        return jdbc.sql("SELECT * FROM inbound_transfer WHERE peer_name = ? AND package_group_id = ?")
                .params(peerName, packageGroupId).query(JdbcInboundRepository::transfer).list().stream().findFirst();
    }

    @Override
    public List<InboundTransfer> findTransfers(InboundTransfer.State state) {
        return jdbc.sql("SELECT * FROM inbound_transfer WHERE state = ? ORDER BY created_at").param(state.name())
                .query(JdbcInboundRepository::transfer).list();
    }

    private static InboundFile file(ResultSet rs, int row) throws SQLException {
        String path = rs.getString("file_path");
        return new InboundFile(rs.getLong("transport_file_id"), rs.getString("peer_name"), rs.getString("file_name"),
                rs.getLong("size_bytes"), InboundFile.State.valueOf(rs.getString("state")),
                path == null ? null : Path.of(path), JdbcTransferRepository.instant(rs, "updated_at"));
    }

    private static InboundTransfer transfer(ResultSet rs, int row) throws SQLException {
        return new InboundTransfer(rs.getObject("transfer_id", UUID.class), rs.getString("peer_name"),
                rs.getString("package_group_id"), rs.getString("study_uid"), rs.getInt("part_count"),
                rs.getString("announcement"), InboundTransfer.State.valueOf(rs.getString("state")),
                rs.getString("error_message"), JdbcTransferRepository.instant(rs, "created_at"),
                JdbcTransferRepository.instant(rs, "updated_at"));
    }
}
