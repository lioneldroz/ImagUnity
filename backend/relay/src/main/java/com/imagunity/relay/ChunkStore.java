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

/** Métadonnées des morceaux et de leurs destinataires (le contenu est sur disque, {@link ChunkFiles}). */
public final class ChunkStore {

    /**
     * Un morceau.
     *
     * @param senderId  identifiant de l'émetteur (capacité)
     * @param boxId     box déposante
     * @param senderKey clé publique Ed25519 de l'émetteur (X.509, base64url)
     * @param size      taille attendue
     * @param digest    empreinte SHA-256 attendue
     * @param uploaded  vrai une fois le contenu reçu et vérifié
     * @param createdAt création
     * @param expiresAt expiration
     */
    public record Chunk(String senderId, UUID boxId, String senderKey, int size, String digest, boolean uploaded,
                        Instant createdAt, Instant expiresAt) {
    }

    /**
     * Un destinataire d'un morceau.
     *
     * @param recipientId  identifiant de retrait (capacité)
     * @param senderId     morceau
     * @param recipientKey clé publique Ed25519 de retrait (X.509, base64url)
     * @param acked        vrai une fois acquitté (accès révoqué)
     */
    public record Recipient(String recipientId, String senderId, String recipientKey, boolean acked) {
    }

    private final JdbcClient jdbc;

    public ChunkStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    public void create(Chunk chunk, List<Recipient> recipients) {
        jdbc.sql("INSERT INTO chunk (sender_id, box_id, sender_key, size, digest, uploaded, created_at, expires_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(chunk.senderId(), chunk.boxId(), chunk.senderKey(), chunk.size(), chunk.digest(),
                        chunk.uploaded(), utc(chunk.createdAt()), utc(chunk.expiresAt()))
                .update();
        for (Recipient r : recipients) {
            jdbc.sql("INSERT INTO chunk_recipient (recipient_id, sender_id, recipient_key, acked) VALUES (?, ?, ?, ?)")
                    .params(r.recipientId(), r.senderId(), r.recipientKey(), r.acked()).update();
        }
    }

    public Optional<Chunk> chunk(String senderId) {
        return jdbc.sql("SELECT * FROM chunk WHERE sender_id = ?").param(senderId).query(ChunkStore::chunk)
                .optional();
    }

    public Optional<Recipient> recipient(String recipientId) {
        return jdbc.sql("SELECT * FROM chunk_recipient WHERE recipient_id = ?").param(recipientId)
                .query(ChunkStore::recipient).optional();
    }

    public List<Recipient> recipients(String senderId) {
        return jdbc.sql("SELECT * FROM chunk_recipient WHERE sender_id = ? ORDER BY recipient_id").param(senderId)
                .query(ChunkStore::recipient).list();
    }

    public void markUploaded(String senderId) {
        jdbc.sql("UPDATE chunk SET uploaded = TRUE WHERE sender_id = ?").param(senderId).update();
    }

    public void ack(String recipientId) {
        jdbc.sql("UPDATE chunk_recipient SET acked = TRUE WHERE recipient_id = ?").param(recipientId).update();
    }

    public void delete(String senderId) {
        jdbc.sql("DELETE FROM chunk_recipient WHERE sender_id = ?").param(senderId).update();
        jdbc.sql("DELETE FROM chunk WHERE sender_id = ?").param(senderId).update();
    }

    /** Volume déposé par une box et encore présent. */
    public long bytesOf(UUID boxId) {
        return jdbc.sql("SELECT COALESCE(SUM(size), 0) FROM chunk WHERE box_id = ?").param(boxId).query(Long.class)
                .single();
    }

    /** Morceaux expirés, ou créés et jamais téléversés depuis {@code incompleteBefore}. */
    public List<String> expired(Instant now, Instant incompleteBefore) {
        return jdbc.sql("SELECT sender_id FROM chunk WHERE expires_at < ? OR (uploaded = FALSE AND created_at < ?)")
                .params(utc(now), utc(incompleteBefore)).query(String.class).list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Chunk chunk(ResultSet rs, int row) throws SQLException {
        return new Chunk(rs.getString("sender_id"), rs.getObject("box_id", UUID.class), rs.getString("sender_key"),
                rs.getInt("size"), rs.getString("digest"), rs.getBoolean("uploaded"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    private static Recipient recipient(ResultSet rs, int row) throws SQLException {
        return new Recipient(rs.getString("recipient_id"), rs.getString("sender_id"), rs.getString("recipient_key"),
                rs.getBoolean("acked"));
    }
}
