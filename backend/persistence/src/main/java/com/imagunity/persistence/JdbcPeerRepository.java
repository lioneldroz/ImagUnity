package com.imagunity.persistence;

import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link PeerRepository} en SQL (H2 dans la box). */
public final class JdbcPeerRepository implements PeerRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate tx;

    public JdbcPeerRepository(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @Override
    public void save(Peer peer) {
        tx.executeWithoutResult(status -> {
            // Ré-appairage : un nom ou un contact déjà connu est remplacé.
            jdbc.sql("DELETE FROM peer WHERE name = ? OR transport_peer_id = ?")
                    .params(peer.name(), peer.transportPeerId()).update();
            jdbc.sql("INSERT INTO peer (name, transport_peer_id, display_name, paired_at, box_id) "
                            + "VALUES (?, ?, ?, ?, ?)")
                    .params(peer.name(), peer.transportPeerId(), peer.displayName(),
                            JdbcTransferRepository.utc(peer.pairedAt()), peer.boxId())
                    .update();
        });
    }

    @Override
    public Optional<Peer> findByName(String name) {
        return jdbc.sql("SELECT * FROM peer WHERE name = ?").param(name).query(JdbcPeerRepository::peer).optional();
    }

    @Override
    public Optional<Peer> findByTransportPeerId(long transportPeerId) {
        return jdbc.sql("SELECT * FROM peer WHERE transport_peer_id = ?").param(transportPeerId)
                .query(JdbcPeerRepository::peer).optional();
    }

    @Override
    public List<Peer> findAll() {
        return jdbc.sql("SELECT * FROM peer ORDER BY name").query(JdbcPeerRepository::peer).list();
    }

    private static Peer peer(ResultSet rs, int row) throws SQLException {
        return new Peer(rs.getString("name"), rs.getLong("transport_peer_id"), rs.getString("display_name"),
                JdbcTransferRepository.instant(rs, "paired_at"), rs.getObject("box_id", java.util.UUID.class));
    }
}
