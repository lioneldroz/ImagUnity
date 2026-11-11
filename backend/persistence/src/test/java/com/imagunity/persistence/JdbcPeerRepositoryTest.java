package com.imagunity.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.domain.peer.Peer;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class JdbcPeerRepositoryTest {

    private final JdbcPeerRepository repository = new JdbcPeerRepository(TestDatabase.migrated());
    private final Instant now = Instant.parse("2026-11-11T12:00:00Z");

    @Test
    void savesAndFindsPeers() {
        Peer b = new Peer("box-b", 5, "box-b", now);
        Peer c = new Peer("box-c", 6, null, now);

        repository.save(c);
        repository.save(b);

        assertThat(repository.findByName("box-b")).contains(b);
        assertThat(repository.findByTransportPeerId(6)).contains(c);
        assertThat(repository.findByName("inconnu")).isEmpty();
        assertThat(repository.findByTransportPeerId(99)).isEmpty();
        assertThat(repository.findAll()).extracting(Peer::name).containsExactly("box-b", "box-c");
    }

    @Test
    void repairingReplacesNameOrContact() {
        repository.save(new Peer("box-b", 5, "ancien", now));

        repository.save(new Peer("box-b", 7, "nouveau", now)); // même nom, nouveau contact
        assertThat(repository.findAll()).singleElement().satisfies(p -> {
            assertThat(p.transportPeerId()).isEqualTo(7);
            assertThat(p.displayName()).isEqualTo("nouveau");
        });

        repository.save(new Peer("box-b2", 7, "nouveau", now)); // même contact, nouveau nom
        assertThat(repository.findAll()).extracting(Peer::name).containsExactly("box-b2");
    }
}
