package com.imagunity.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.SignedDocument;
import com.imagunity.domain.peer.Peer;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DirectoryPeerAuthorizationTest {

    private final DirectoryFixture fixture = new DirectoryFixture();
    private final BoxIdentity box = fixture.box();
    private final Peer unbound = new Peer("box-b", 5, "box-b", DirectoryFixture.NOW);

    private DirectoryPeerAuthorization at(java.time.Instant now) {
        return new DirectoryPeerAuthorization(fixture.directory(Clock.fixed(now, ZoneOffset.UTC)));
    }

    @Test
    void onlyBoundActiveBoxesAreAllowed() {
        SignedDocument entry = fixture.enroll(box);
        Peer bound = unbound.boundTo(box.boxId());

        assertThat(at(DirectoryFixture.NOW).refusal(bound)).isEmpty();
        assertThat(at(DirectoryFixture.NOW).refusal(unbound)).hasValueSatisfying(r -> assertThat(r)
                .contains("pas encore vérifié"));
        assertThat(at(DirectoryFixture.NOW).refusal(unbound.boundTo(UUID.randomUUID())))
                .hasValueSatisfying(r -> assertThat(r).contains("absente de l'annuaire"));
        assertThat(at(DirectoryFixture.NOW.plus(Duration.ofDays(30))).refusal(bound))
                .hasValueSatisfying(r -> assertThat(r).contains("expirée"));
        assertThat(at(DirectoryFixture.NOW.minusSeconds(1)).refusal(bound))
                .hasValueSatisfying(r -> assertThat(r).contains("pas encore valide"));

        fixture.published.add(fixture.organization.revoke(entry, DirectoryFixture.NOW, "Box volée"));
        assertThat(at(DirectoryFixture.NOW).refusal(bound)).hasValueSatisfying(r -> assertThat(r).contains("révoquée"));
        assertThat(PeerAuthorization.allowAll().refusal(unbound)).isEmpty();
        assertThat(List.of(box.boxId())).isNotEmpty();
    }
}
