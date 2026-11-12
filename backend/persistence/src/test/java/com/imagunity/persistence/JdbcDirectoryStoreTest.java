package com.imagunity.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.HybridSignature;
import com.imagunity.directory.SignedDocument;
import com.imagunity.domain.peer.Peer;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JdbcDirectoryStoreTest {

    private final javax.sql.DataSource dataSource = TestDatabase.migrated();
    private final JdbcDirectoryStore store = new JdbcDirectoryStore(dataSource);
    private final Instant now = Instant.parse("2026-11-12T10:00:00Z");

    @Test
    void remembersTheHighestSerialOfEachBox() {
        UUID box = UUID.randomUUID();

        assertThat(store.highest(box)).isEmpty();
        store.record(box, 2);
        store.record(box, 1);
        store.record(box, 3);

        assertThat(store.highest(box)).hasValue(3);
        assertThat(store.highest(UUID.randomUUID())).isEmpty();
    }

    @Test
    void cachesOneEntryPerBox() {
        UUID box = UUID.randomUUID();
        SignedDocument first = new SignedDocument("{\"serial\":1}", new HybridSignature("IU1", "a", "b"));
        SignedDocument second = new SignedDocument("{\"serial\":2}", HybridKeyPair.generate().sign(new byte[] {1}));

        store.save(box, 1, first, now);
        store.save(box, 2, second, now.plusSeconds(60));

        assertThat(store.entries()).containsExactly(second);
    }

    @Test
    void peerKeepsItsDirectoryBox() {
        JdbcPeerRepository peers = new JdbcPeerRepository(dataSource);
        UUID box = UUID.randomUUID();

        peers.save(new Peer("box-b", 5, "box-b", now).boundTo(box));
        peers.save(new Peer("box-c", 6, "box-c", now));

        assertThat(peers.findByName("box-b")).hasValueSatisfying(p -> assertThat(p.boxId()).isEqualTo(box));
        assertThat(peers.findByName("box-c")).hasValueSatisfying(p -> assertThat(p.boxId()).isNull());
        peers.delete("box-c");
        assertThat(peers.findByName("box-c")).isEmpty();
    }
}
