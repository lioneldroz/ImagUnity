package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Preuves de fraîcheur des annuaires : délégation, vérification, entrées en retard, annuaire figé. */
class FreshnessTest {

    private static final HybridKeyPair A_KEYS = HybridKeyPair.generate();
    private static final HybridKeyPair B_KEYS = HybridKeyPair.generate();
    private static final Organization A = new Organization("grand-est", A_KEYS);
    private static final Organization B = new Organization("bourgogne-fc", B_KEYS);
    private static final String RELAY_A = "https://relais.grand-est.example";
    private static final String RELAY_B = "https://relais.bourgogne-fc.example";
    private static final Duration DAYS_30 = Duration.ofDays(30);

    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-12-01T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final LocalDirectoryTest.MemoryCache cache = new LocalDirectoryTest.MemoryCache();
    private final SerialMemory serials = SerialMemory.inMemory();
    private final HybridKeyPair freshKeyA = HybridKeyPair.generate();
    private final HybridKeyPair freshKeyB = HybridKeyPair.generate();

    private SignedDocument entry(Organization o, UUID box, String relay, long serial, String... groups) {
        return o.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), HybridKemKeyPair.generate().publicKey(), box,
                "Box " + box.toString().substring(0, 4), Map.of("iu", relay + "/iu/v1/queues/q"), clock.now),
                clock.now, Duration.ofDays(90), serial, List.of(groups), "Box " + box.toString().substring(0, 4));
    }

    private Freshness proofA(Map<UUID, Long> entries, Map<String, Long> recognitions) {
        return new Freshness(A.delegateFreshness(freshKeyA.publicKey(), clock.now.minusSeconds(60), DAYS_30, 1),
                FreshnessProof.sign(freshKeyA, A.id(), clock.now, entries, recognitions));
    }

    private Freshness proofB(Map<UUID, Long> entries) {
        return new Freshness(B.delegateFreshness(freshKeyB.publicKey(), clock.now.minusSeconds(60), DAYS_30, 1),
                FreshnessProof.sign(freshKeyB, B.id(), clock.now, entries, Map.of()));
    }

    private LocalDirectory directory(Duration maxAge) {
        return new LocalDirectory(new EntryVerifier(A.id(), A.publicKey(), serials, clock), cache, clock)
                .federate(new RecognitionVerifier(A.id(), A.publicKey(), serials), serials)
                .checkFreshness(A.id(), A.publicKey(), maxAge);
    }

    @Test
    void proofsAreSignedByAKeyDelegatedByTheResponsible() {
        SignedDocument delegation = A.delegateFreshness(freshKeyA.publicKey(), clock.now, DAYS_30, 3);
        FreshnessDelegation d = Json.read(delegation.payload(), FreshnessDelegation.class, "d");
        assertThat(d.serial()).isEqualTo(3);
        assertThat(d.coversAt(clock.now)).isTrue();
        assertThat(d.coversAt(clock.now.plus(DAYS_30))).isFalse();
        assertThat(d.coversAt(clock.now.minusSeconds(1))).isFalse();
        UUID box = UUID.randomUUID();
        Freshness f = new Freshness(delegation, FreshnessProof.sign(freshKeyA, A.id(), clock.now, Map.of(box, 4L),
                Map.of("ab", 2L)));
        FreshnessProof p = f.verify(A.id(), A.publicKey(), clock.now);
        assertThat(p.entries()).containsEntry(box, 4L);
        assertThat(p.recognitions()).containsEntry("ab", 2L);
        assertThat(Freshness.fromJson(f.toJson())).isEqualTo(f);
        assertThat(f.delegationContent().publicKey().fingerprint()).isEqualTo(freshKeyA.publicKey().fingerprint());

        assertThatThrownBy(() -> f.verify(A.id(), B.publicKey(), clock.now)).hasMessageContaining("non signée");
        assertThatThrownBy(() -> f.verify("autre", A.publicKey(), clock.now)).hasMessageContaining("autre organisation");
        assertThatThrownBy(() -> new Freshness(delegation, FreshnessProof.sign(freshKeyB, A.id(), clock.now, Map.of(),
                Map.of())).verify(A.id(), A.publicKey(), clock.now)).hasMessageContaining("clé déléguée");
        assertThatThrownBy(() -> new Freshness(delegation, FreshnessProof.sign(freshKeyA, "autre", clock.now, Map.of(),
                Map.of())).verify(A.id(), A.publicKey(), clock.now)).hasMessageContaining("autre organisation");
        assertThatThrownBy(() -> new Freshness(delegation, FreshnessProof.sign(freshKeyA, A.id(),
                clock.now.plus(Duration.ofHours(1)), Map.of(), Map.of())).verify(A.id(), A.publicKey(), clock.now))
                .hasMessageContaining("futur");
        assertThatThrownBy(() -> new Freshness(delegation, FreshnessProof.sign(freshKeyA, A.id(),
                clock.now.plus(Duration.ofDays(31)), Map.of(), Map.of())).verify(A.id(), A.publicKey(),
                clock.now.plus(Duration.ofDays(31)))).hasMessageContaining("hors de la validité");
        assertThatThrownBy(() -> A.delegateFreshness(A.publicKey(), clock.now, DAYS_30, 1))
                .hasMessageContaining("distincte");
        HybridPublicKey.Encoded k = freshKeyA.publicKey().encoded();
        assertThatThrownBy(() -> new FreshnessDelegation("IU1", "autre", "a", k, 1, clock.now, clock.now.plusSeconds(1)))
                .hasMessageContaining("type");
        assertThatThrownBy(() -> new FreshnessDelegation("IU1", "freshness-key", "a", k, 0, clock.now,
                clock.now.plusSeconds(1))).hasMessageContaining("commence à 1");
        assertThatThrownBy(() -> new FreshnessDelegation("IU1", "freshness-key", "a", k, 1, clock.now, clock.now))
                .hasMessageContaining("antérieure");
        assertThatThrownBy(() -> new FreshnessProof("IU1", "autre", "a", clock.now, null, null))
                .hasMessageContaining("type");
        assertThat(new FreshnessProof("IU1", "freshness", "a", clock.now, null, null).entries()).isEmpty();
    }

    @Test
    void anEntryOlderThanTheAnnouncedOneIsUnusableUntilTheNewOneArrives() {
        LocalDirectory directory = directory(Duration.ofHours(48));
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        SignedDocument first = entry(A, other, RELAY_A, 1);
        directory.synchronize(() -> List.of(entry(A, self, RELAY_A, 1), first));
        assertThat(directory.freshnessChecked()).isTrue();
        assertThat(directory.localFingerprint()).hasValue(A.publicKey().fingerprint());

        // L'annuaire annonce le n° 2 de l'autre box (une révocation, peut-être) mais ne le sert pas.
        assertThat(directory.synchronizeFreshness(() -> Optional.of(proofA(Map.of(self, 1L, other, 2L), Map.of())))
                .accepted()).isEqualTo(1);
        assertThat(directory.entry(other)).isEmpty();
        assertThat(directory.entries()).extracting(BoxEntry::boxId).containsExactly(self);
        assertThat(directory.exchangeRefusal(self, other)).hasValueSatisfying(m -> assertThat(m)
                .contains("plus récente").contains("pas encore reçue"));
        directory.synchronize(() -> List.of(A.revoke(first, clock.now, "Box volée")));
        assertThat(directory.entry(other)).hasValueSatisfying(e -> assertThat(e.revoked()).isTrue());
        assertThat(directory.exchangeRefusal(self, other)).as("à jour : l'état est contrôlé à part").isEmpty();
        assertThat(directory.localFreshness()).isPresent();
        assertThat(directory.freshnessSync(A.publicKey().fingerprint()).error()).isNull();
    }

    @Test
    void aFrozenDirectoryStopsExchangesOnceAProofHasBeenSeen() {
        LocalDirectory directory = directory(Duration.ofHours(48));
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        directory.synchronize(() -> List.of(entry(A, self, RELAY_A, 1), entry(A, other, RELAY_A, 1)));
        assertThat(directory.synchronizeFreshness(Optional::empty).error()).contains("sans preuve");
        clock.now = clock.now.plus(Duration.ofDays(10));
        assertThat(directory.exchangeRefusal(self, other)).as("jamais de preuve : pas d'arrêt").isEmpty();

        directory.synchronizeFreshness(() -> Optional.of(proofA(Map.of(), Map.of())));
        String fp = A.publicKey().fingerprint();
        assertThat(directory.stale(fp)).isFalse();
        clock.now = clock.now.plus(Duration.ofHours(49));
        assertThat(directory.stale(fp)).isTrue();
        assertThat(directory.exchangeRefusal(self, other)).hasValueSatisfying(m -> assertThat(m)
                .contains("sans preuve de fraîcheur récente"));
        // Une preuve plus ancienne que la précédente est refusée ; une nouvelle relance les échanges.
        Freshness older = new Freshness(A.delegateFreshness(freshKeyA.publicKey(), clock.now.minus(Duration.ofDays(5)),
                DAYS_30, 1), FreshnessProof.sign(freshKeyA, A.id(), clock.now.minus(Duration.ofDays(4)), Map.of(),
                Map.of()));
        assertThat(directory.synchronizeFreshness(() -> Optional.of(older)).error()).contains("plus ancienne");
        directory.synchronizeFreshness(() -> Optional.of(proofA(Map.of(), Map.of())));
        assertThat(directory.exchangeRefusal(self, other)).isEmpty();
        assertThat(directory.synchronizeFreshness(() -> {
            throw new DirectoryException("injoignable");
        }).error()).isEqualTo("injoignable");
        assertThat(directory.synchronizeFreshness(() -> Optional.of(proofB(Map.of()))).error())
                .contains("autre organisation");

        SerialMemory fresh = SerialMemory.inMemory();
        LocalDirectory never = new LocalDirectory(new EntryVerifier(A.id(), A.publicKey(), fresh, clock),
                new LocalDirectoryTest.MemoryCache(), clock).checkFreshness(A.id(), A.publicKey(), Duration.ZERO);
        never.synchronize(() -> List.of(entry(A, self, RELAY_A, 1), entry(A, other, RELAY_A, 1)));
        never.synchronizeFreshness(() -> Optional.of(proofA(Map.of(), Map.of())));
        clock.now = clock.now.plus(Duration.ofDays(60));
        assertThat(never.exchangeRefusal(self, other)).as("âge maximal nul : pas d'arrêt").isEmpty();
    }

    @Test
    void partnerProofsGuardPartnerEntriesAndAgreements() {
        LocalDirectory directory = directory(Duration.ofHours(48));
        UUID self = UUID.randomUUID();
        UUID nord = UUID.randomUUID();
        String fpB = B.publicKey().fingerprint();
        directory.synchronize(() -> List.of(entry(A, self, RELAY_A, 1)));
        assertThat(directory.synchronizePartnerFreshness(fpB, () -> Optional.of(proofB(Map.of()))).error())
                .isEqualTo("Organisation non reconnue");
        Recognition.Partner partner = new Recognition.Partner(B.id(), B.publicKey().encoded(),
                "https://annuaire.bourgogne-fc.example", List.of(RELAY_B));
        SignedDocument recognition = A.recognize(partner, new Recognition.Scope(List.of("*"), List.of("*")), clock.now,
                Duration.ofDays(90));
        directory.synchronizeRecognitions(() -> List.of(recognition));
        directory.synchronizePartner(fpB, () -> List.of(entry(B, nord, RELAY_B, 1)));
        assertThat(directory.exchangeRefusal(self, nord)).isEmpty();

        // Preuve du partenaire, relayée par notre annuaire : elle annonce le n° 2 de la box nord.
        assertThat(directory.synchronizePartnerFreshness(fpB, () -> Optional.of(proofB(Map.of(nord, 2L)))).accepted())
                .isEqualTo(1);
        assertThat(directory.freshness(fpB)).isPresent();
        assertThat(directory.entry(nord)).isEmpty();
        assertThat(directory.exchangeRefusal(self, nord)).hasValueSatisfying(m -> assertThat(m)
                .contains("annoncée par l'annuaire de « bourgogne-fc »"));
        assertThat(directory.synchronizePartnerFreshness(fpB, () -> Optional.of(proofA(Map.of(), Map.of()))).error())
                .as("preuve d'une autre organisation").contains("autre organisation");
        directory.synchronizePartner(fpB, () -> List.of(entry(B, nord, RELAY_B, 2)));
        assertThat(directory.exchangeRefusal(self, nord)).isEmpty();

        // Notre preuve annonce une reconnaissance plus récente que celle reçue : accord suspendu.
        directory.synchronizeFreshness(() -> Optional.of(proofA(Map.of(self, 1L), Map.of(fpB, 2L))));
        assertThat(directory.entry(nord)).isEmpty();
        assertThat(directory.exchangeRefusal(self, nord)).hasValueSatisfying(m -> assertThat(m)
                .contains("Version plus récente de l'accord"));
        directory.synchronizeRecognitions(() -> List.of(A.renewRecognition(recognition, partner,
                new Recognition.Scope(List.of("*"), List.of("*")), clock.now, Duration.ofDays(90))));
        assertThat(directory.exchangeRefusal(self, nord)).isEmpty();

        // Annuaire du partenaire figé.
        clock.now = clock.now.plus(Duration.ofHours(49));
        directory.synchronizeFreshness(() -> Optional.of(proofA(Map.of(self, 1L), Map.of(fpB, 2L))));
        assertThat(directory.exchangeRefusal(self, nord)).hasValueSatisfying(m -> assertThat(m)
                .contains("« bourgogne-fc »").contains("sans preuve de fraîcheur récente"));
    }

    @Test
    void proofsAreKeptInTheCacheAcrossRestarts() {
        LocalDirectory first = directory(Duration.ofHours(48));
        UUID self = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String fpB = B.publicKey().fingerprint();
        first.synchronize(() -> List.of(entry(A, self, RELAY_A, 1), entry(A, other, RELAY_A, 1)));
        first.synchronizeRecognitions(() -> List.of(A.recognize(new Recognition.Partner(B.id(), B.publicKey().encoded(),
                "https://annuaire.b.example", List.of(RELAY_B)), new Recognition.Scope(List.of("*"), List.of("*")),
                clock.now, Duration.ofDays(90))));
        first.synchronizeFreshness(() -> Optional.of(proofA(Map.of(other, 2L), Map.of())));
        first.synchronizePartnerFreshness(fpB, () -> Optional.of(proofB(Map.of())));
        // Preuve en cache sans sa délégation : ignorée.
        cache.documents.put(UUID.randomUUID(), FreshnessProof.sign(freshKeyB, "orpheline", clock.now, Map.of(),
                Map.of()));
        // Preuve en cache avec une délégation falsifiée : écartée.
        cache.documents.put(UUID.randomUUID(), FreshnessProof.sign(freshKeyB, "falsifiee", clock.now, Map.of(), Map.of()));
        cache.documents.put(UUID.randomUUID(), SignedDocument.sign(B.delegateFreshness(freshKeyB.publicKey(), clock.now,
                DAYS_30, 1).payload().replace("bourgogne-fc", "falsifiee"), B_KEYS));

        LocalDirectory restarted = new LocalDirectory(new EntryVerifier(A.id(), A.publicKey(), SerialMemory.inMemory(),
                clock), cache, clock).federate(new RecognitionVerifier(A.id(), A.publicKey(), SerialMemory.inMemory()),
                SerialMemory.inMemory()).checkFreshness(A.id(), A.publicKey(), Duration.ofHours(48));
        restarted.load();
        assertThat(restarted.localFreshness()).isPresent();
        assertThat(restarted.freshness(fpB)).isPresent();
        assertThat(restarted.entry(other)).as("toujours en retard après redémarrage").isEmpty();
        assertThatThrownBy(() -> new LocalDirectory(new EntryVerifier(A.id(), A.publicKey(), serials, clock), cache,
                clock).synchronizeFreshness(Optional::empty)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void partnersWithTheSameNameKeepTheirOwnProofsAcrossRestarts() {
        // Deux organisations partenaires distinctes (clés différentes) qui portent le même nom : le nom n'est qu'une
        // étiquette, chaque preuve reste rattachée à l'empreinte de son organisation.
        HybridKeyPair homonymKeys = HybridKeyPair.generate();
        Organization homonym = new Organization(B.id(), homonymKeys);
        HybridKeyPair freshKeyHomonym = HybridKeyPair.generate();
        String fpB = B.publicKey().fingerprint();
        String fpHomonym = homonym.publicKey().fingerprint();
        LocalDirectory first = directory(Duration.ofHours(48));
        first.synchronizeRecognitions(() -> List.of(
                A.recognize(new Recognition.Partner(B.id(), B.publicKey().encoded(), "https://annuaire.b.example",
                        List.of(RELAY_B)), new Recognition.Scope(List.of("*"), List.of("*")), clock.now,
                        Duration.ofDays(90)),
                A.recognize(new Recognition.Partner(homonym.id(), homonym.publicKey().encoded(),
                        "https://annuaire.homonyme.example", List.of(RELAY_B)),
                        new Recognition.Scope(List.of("*"), List.of("*")), clock.now, Duration.ofDays(90))));
        assertThat(first.synchronizePartnerFreshness(fpB, () -> Optional.of(proofB(Map.of()))).accepted())
                .isEqualTo(1);
        assertThat(first.synchronizePartnerFreshness(fpHomonym, () -> Optional.of(new Freshness(
                homonym.delegateFreshness(freshKeyHomonym.publicKey(), clock.now.minusSeconds(60), DAYS_30, 1),
                FreshnessProof.sign(freshKeyHomonym, homonym.id(), clock.now.minusSeconds(30), Map.of(),
                        Map.of())))).accepted()).isEqualTo(1);

        LocalDirectory restarted = new LocalDirectory(new EntryVerifier(A.id(), A.publicKey(), SerialMemory.inMemory(),
                clock), cache, clock).federate(new RecognitionVerifier(A.id(), A.publicKey(), SerialMemory.inMemory()),
                SerialMemory.inMemory()).checkFreshness(A.id(), A.publicKey(), Duration.ofHours(48));
        restarted.load();

        assertThat(restarted.freshness(fpB)).as("preuve de bourgogne-fc").hasValueSatisfying(p ->
                assertThat(p.issuedAt()).isEqualTo(clock.now));
        assertThat(restarted.freshness(fpHomonym)).as("preuve de l'homonyme").hasValueSatisfying(p ->
                assertThat(p.issuedAt()).isEqualTo(clock.now.minusSeconds(30)));
    }

    @Test
    void theClientReadsFreshnessProofs() throws Exception {
        Freshness f = proofA(Map.of(), Map.of());
        int[] status = {200};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/", exchange -> {
            byte[] body = f.toJson().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status[0], status[0] == 200 ? body.length : -1);
            if (status[0] == 200) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            DirectoryClient client = new DirectoryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    Duration.ofSeconds(5));
            assertThat(client.fetchFreshness()).hasValue(f);
            assertThat(client.fetchPartnerFreshness(B.publicKey().fingerprint())).hasValue(f);
            assertThatThrownBy(() -> client.fetchPartnerFreshness("x")).hasMessageContaining("mal formée");
            status[0] = 404;
            assertThat(client.fetchFreshness()).isEmpty();
            status[0] = 500;
            assertThatThrownBy(client::fetchFreshness).hasMessageContaining("500");
        } finally {
            server.stop(0);
        }
    }
}
