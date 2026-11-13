package com.imagunity.relay;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Files de réception et messages en attente. */
public final class QueueStore {

    /**
     * Une file.
     *
     * @param recvId    identifiant de relève (capacité)
     * @param sendId    identifiant de dépôt (publié dans l'annuaire)
     * @param boxId     box propriétaire
     * @param queueKey  clé publique Ed25519 de la file (X.509, base64url)
     * @param createdAt création
     */
    public record Queue(String recvId, String sendId, UUID boxId, String queueKey, Instant createdAt) {
    }

    /**
     * Un message en attente.
     *
     * @param messageId  identifiant attribué par le relais
     * @param content    enveloppe chiffrée
     * @param receivedAt dépôt
     */
    public record Message(String messageId, byte[] content, Instant receivedAt) {
    }

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public QueueStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    public void create(Queue queue) {
        jdbc.sql("INSERT INTO queue (recv_id, send_id, box_id, queue_key, created_at) VALUES (?, ?, ?, ?, ?)")
                .params(queue.recvId(), queue.sendId(), queue.boxId(), queue.queueKey(), utc(queue.createdAt()))
                .update();
    }

    public Optional<Queue> byRecvId(String recvId) {
        return jdbc.sql("SELECT * FROM queue WHERE recv_id = ?").param(recvId).query(QueueStore::queue).optional();
    }

    public Optional<Queue> bySendId(String sendId) {
        return jdbc.sql("SELECT * FROM queue WHERE send_id = ?").param(sendId).query(QueueStore::queue).optional();
    }

    /** Remplace la clé de relève d'une file (nouvelle demande de création par la box qui la possède). */
    public void updateKey(String sendId, String queueKey) {
        jdbc.sql("UPDATE queue SET queue_key = ? WHERE send_id = ?").params(queueKey, sendId).update();
    }

    public int countOf(UUID boxId) {
        return jdbc.sql("SELECT COUNT(*) FROM queue WHERE box_id = ?").param(boxId).query(Integer.class).single();
    }

    /**
     * Renouvelle les identifiants d'une file ; ses messages en attente suivent. En une transaction : une panne ou deux
     * renouvellements simultanés ne laissent ni deux files ni des messages perdus.
     *
     * @return faux si la file n'existait plus (déjà renouvelée), rien n'est alors changé
     */
    public boolean rotate(Queue queue, String newRecvId, String newSendId) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            jdbc.sql("INSERT INTO queue (recv_id, send_id, box_id, queue_key, created_at) VALUES (?, ?, ?, ?, ?)")
                    .params(newRecvId, newSendId, queue.boxId(), queue.queueKey(), utc(queue.createdAt())).update();
            jdbc.sql("UPDATE queue_message SET recv_id = ? WHERE recv_id = ?").params(newRecvId, queue.recvId())
                    .update();
            if (jdbc.sql("DELETE FROM queue WHERE recv_id = ?").param(queue.recvId()).update() != 1) {
                status.setRollbackOnly(); // déjà renouvelée par une autre requête
                return false;
            }
            return true;
        }));
    }

    public void deposit(String recvId, String messageId, UUID senderBox, byte[] content, Instant at,
                        Instant expiresAt) {
        jdbc.sql("INSERT INTO queue_message (message_id, recv_id, sender_box, content, received_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)")
                .params(messageId, recvId, senderBox, content, utc(at), utc(expiresAt)).update();
    }

    /** Messages en attente déposés par cette box dans cette file. */
    public int pendingFrom(String recvId, UUID senderBox) {
        return jdbc.sql("SELECT COUNT(*) FROM queue_message WHERE recv_id = ? AND sender_box = ?")
                .params(recvId, senderBox).query(Integer.class).single();
    }

    public int pending(String recvId) {
        return jdbc.sql("SELECT COUNT(*) FROM queue_message WHERE recv_id = ?").param(recvId).query(Integer.class)
                .single();
    }

    /** Messages en attente non expirés, les plus anciens d'abord. */
    public List<Message> messages(String recvId, Instant now, int limit) {
        return jdbc.sql("SELECT message_id, content, received_at FROM queue_message WHERE recv_id = ? "
                        + "AND expires_at > ? ORDER BY received_at, message_id LIMIT ?")
                .params(recvId, utc(now), limit)
                .query((rs, row) -> new Message(rs.getString("message_id"), rs.getBytes("content"),
                        rs.getObject("received_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** @return vrai si le message existait dans cette file */
    public boolean delete(String recvId, String messageId) {
        return jdbc.sql("DELETE FROM queue_message WHERE recv_id = ? AND message_id = ?").params(recvId, messageId)
                .update() == 1;
    }

    public int purge(Instant now) {
        return jdbc.sql("DELETE FROM queue_message WHERE expires_at < ?").param(utc(now)).update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Queue queue(ResultSet rs, int row) throws SQLException {
        return new Queue(rs.getString("recv_id"), rs.getString("send_id"), rs.getObject("box_id", UUID.class),
                rs.getString("queue_key"), rs.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
