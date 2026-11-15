package com.imagunity.directoryserver;

import com.imagunity.directory.Recognition;
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

/**
 * Reconnaissances publiées (historique compris ; la courante est celle de numéro le plus haut pour chaque partenaire)
 * et entrées des partenaires relayées.
 */
@Repository
public class RecognitionStore {

    private final JdbcClient jdbc;

    public RecognitionStore(DataSource dataSource) {
        this.jdbc = JdbcClient.create(dataSource);
    }

    void save(Recognition recognition, SignedDocument document, Instant publishedAt) {
        jdbc.sql("INSERT INTO recognition (partner_fingerprint, serial, partner_organization, revoked, valid_until, "
                        + "document, published_at) VALUES (?, ?, ?, ?, ?, ?, ?)")
                .params(recognition.partner().fingerprint(), recognition.serial(),
                        recognition.partner().organization(), recognition.revoked(), utc(recognition.validUntil()),
                        document.toJson(), utc(publishedAt))
                .update();
    }

    OptionalLong highestSerial(String partner) {
        return jdbc.sql("SELECT serial FROM recognition WHERE partner_fingerprint = ? ORDER BY serial DESC LIMIT 1")
                .param(partner).query(Long.class).optional().map(OptionalLong::of).orElse(OptionalLong.empty());
    }

    Optional<SignedDocument> find(String partner, long serial) {
        return jdbc.sql("SELECT document FROM recognition WHERE partner_fingerprint = ? AND serial = ?")
                .params(partner, serial).query(String.class).optional().map(SignedDocument::fromJson);
    }

    /** Reconnaissance courante de chaque partenaire. */
    List<SignedDocument> current() {
        return jdbc.sql("SELECT r.document FROM recognition r WHERE r.serial = (SELECT MAX(serial) FROM recognition m "
                        + "WHERE m.partner_fingerprint = r.partner_fingerprint) ORDER BY r.partner_organization, "
                        + "r.partner_fingerprint")
                .query(String.class).list().stream().map(SignedDocument::fromJson).toList();
    }

    /** Plus haut numéro publié de chaque reconnaissance, par empreinte du partenaire (preuve de fraîcheur). */
    java.util.Map<String, Long> highestSerials() {
        java.util.Map<String, Long> serials = new java.util.TreeMap<>();
        jdbc.sql("SELECT partner_fingerprint, MAX(serial) AS serial FROM recognition GROUP BY partner_fingerprint")
                .query((rs, n) -> serials.put(rs.getString("partner_fingerprint"), rs.getLong("serial"))).list();
        return serials;
    }

    /** Retient la preuve de fraîcheur relayée d'un partenaire, si elle est plus récente que la précédente. */
    void savePartnerFreshness(String partner, com.imagunity.directory.Freshness freshness, Instant issuedAt,
                              Instant fetchedAt) {
        Optional<OffsetDateTime> stored = jdbc.sql("SELECT issued_at FROM partner_freshness WHERE partner_fingerprint = ?")
                .param(partner).query(OffsetDateTime.class).optional();
        if (stored.isEmpty()) {
            jdbc.sql("INSERT INTO partner_freshness (partner_fingerprint, freshness, issued_at, fetched_at) "
                    + "VALUES (?, ?, ?, ?)").params(partner, freshness.toJson(), utc(issuedAt), utc(fetchedAt)).update();
        } else if (stored.get().toInstant().isBefore(issuedAt)) {
            jdbc.sql("UPDATE partner_freshness SET freshness = ?, issued_at = ?, fetched_at = ? WHERE "
                    + "partner_fingerprint = ?").params(freshness.toJson(), utc(issuedAt), utc(fetchedAt), partner)
                    .update();
        }
    }

    Optional<com.imagunity.directory.Freshness> partnerFreshness(String partner) {
        return jdbc.sql("SELECT freshness FROM partner_freshness WHERE partner_fingerprint = ?").param(partner)
                .query(String.class).optional().map(com.imagunity.directory.Freshness::fromJson);
    }

    /** Retient l'entrée relayée d'une box de partenaire, si elle est plus récente que celle déjà gardée. */
    boolean savePartnerEntry(String partner, UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
        Optional<Long> stored = jdbc.sql("SELECT serial FROM partner_entry WHERE partner_fingerprint = ? AND box_id = ?")
                .params(partner, boxId).query(Long.class).optional();
        if (stored.isEmpty()) {
            jdbc.sql("INSERT INTO partner_entry (partner_fingerprint, box_id, serial, document, fetched_at, seq) "
                    + "VALUES (?, ?, ?, ?, ?, nextval('directory_seq'))").params(partner, boxId, serial, document.toJson(), utc(fetchedAt))
                    .update();
            return true;
        }
        if (serial <= stored.get()) {
            return false;
        }
        jdbc.sql("UPDATE partner_entry SET serial = ?, document = ?, fetched_at = ?, seq = nextval('directory_seq') "
                + "WHERE partner_fingerprint = ? "
                + "AND box_id = ?").params(serial, document.toJson(), utc(fetchedAt), partner, boxId).update();
        return true;
    }

    /** Curseur courant des entrées relayées d'un partenaire (0 sans entrée). */
    long partnerCursor(String partner) {
        return jdbc.sql("SELECT COALESCE(MAX(seq), 0) FROM partner_entry WHERE partner_fingerprint = ?")
                .param(partner).query(Long.class).single();
    }

    /** Entrées relayées d'un partenaire. */
    List<SignedDocument> partnerEntries(String partner) {
        return partnerEntriesSince(partner, 0);
    }

    /** Entrées relayées d'un partenaire, reçues ou changées après le curseur {@code since}. */
    List<SignedDocument> partnerEntriesSince(String partner, long since) {
        return jdbc.sql("SELECT document FROM partner_entry WHERE partner_fingerprint = ? AND seq > ? ORDER BY box_id")
                .params(partner, since).query(String.class).list().stream().map(SignedDocument::fromJson).toList();
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
