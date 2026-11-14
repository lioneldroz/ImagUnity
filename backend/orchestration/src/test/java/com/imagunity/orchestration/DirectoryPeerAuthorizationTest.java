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

    @Test
    void partnerBoxesNeedAnAgreementCoveringBothBoxes() {
        java.time.Instant now = DirectoryFixture.NOW;
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        BoxIdentity self = fixture.box();
        fixture.published.add(fixture.organization.enroll(com.imagunity.directory.EnrollmentRequest.create(self.keys(),
                self.encryption().publicKey(), self.boxId(), "Box locale", java.util.Map.of(), now), now,
                Duration.ofDays(30), 1, java.util.List.of("imagerie"), "Box locale"));
        com.imagunity.directory.Organization partner = new com.imagunity.directory.Organization("bourgogne-fc",
                com.imagunity.directory.HybridKeyPair.generate());
        UUID foreign = UUID.randomUUID();
        String relay = "https://relais.bourgogne-fc.example";
        SignedDocument entry = partner.enroll(com.imagunity.directory.EnrollmentRequest.create(
                com.imagunity.directory.HybridKeyPair.generate(), foreign, "Box de Bourgogne",
                java.util.Map.of("iu", relay + "/iu/v1/queues/q"), now), now, Duration.ofDays(30), 1);
        com.imagunity.directory.LocalDirectory directory = fixture.directory(clock);
        directory.federate(new com.imagunity.directory.RecognitionVerifier("chu-exemple",
                fixture.organization.publicKey(), com.imagunity.directory.SerialMemory.inMemory()),
                com.imagunity.directory.SerialMemory.inMemory());
        com.imagunity.directory.Recognition.Partner p = new com.imagunity.directory.Recognition.Partner(partner.id(),
                partner.publicKey().encoded(), "https://annuaire.bourgogne-fc.example", List.of(relay));
        Peer bound = new Peer("bourgogne", 6, "bourgogne", now).boundTo(foreign);

        // Accord dont le périmètre exclut les groupes de cette box.
        directory.synchronizeRecognitions(() -> List.of(fixture.organization.recognize(p,
                new com.imagunity.directory.Recognition.Scope(List.of("urgences"), List.of("*")), now,
                Duration.ofDays(90))));
        directory.synchronizePartner(p.fingerprint(), () -> List.of(entry));
        DirectoryPeerAuthorization authorization = new DirectoryPeerAuthorization(directory, self.boxId());
        assertThat(authorization.refusal(bound)).hasValueSatisfying(r -> assertThat(r)
                .contains("« bourgogne »").contains("pas dans les groupes autorisés"));
        assertThat(new DirectoryPeerAuthorization(directory).refusal(bound)).as("sans box locale : statut seul")
                .isEmpty();

        // Accord révoqué : la box du partenaire n'est plus utilisable, le motif le dit.
        SignedDocument first = directory.recognitions().isEmpty() ? null : fixture.organization.recognize(p,
                new com.imagunity.directory.Recognition.Scope(List.of("urgences"), List.of("*")), now,
                Duration.ofDays(90));
        assertThat(first).isNotNull();
        directory.synchronizeRecognitions(() -> List.of(fixture.organization.revokeRecognition(
                fixture.organization.renewRecognition(first, p, new com.imagunity.directory.Recognition.Scope(
                        List.of("*"), List.of("*")), now, Duration.ofDays(90)), now, "fin")));
        assertThat(authorization.refusal(bound)).hasValueSatisfying(r -> assertThat(r).contains("révoqué"));
        assertThat(authorization.refusal(new Peer("x", 7, "x", now).boundTo(box.boxId()))).as("box locale inconnue")
                .hasValueSatisfying(r -> assertThat(r).contains("absente"));
    }
}
