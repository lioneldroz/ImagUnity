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
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Fédération : reconnaissances de partenaires et annuaire fédéré (données synthétiques). */
class FederationTest {

    private static final HybridKeyPair A_KEYS = HybridKeyPair.generate();
    private static final HybridKeyPair B_KEYS = HybridKeyPair.generate();
    private static final HybridKeyPair C_KEYS = HybridKeyPair.generate();
    private static final Organization A = new Organization("grand-est", A_KEYS);
    private static final Organization B = new Organization("bourgogne-fc", B_KEYS);
    private static final Organization C = new Organization("chu-exemple", C_KEYS);
    private static final String RELAY_B = "https://relais.bourgogne-fc.example";
    private static final Duration DAYS_90 = Duration.ofDays(90);

    /** Horloge réglable. */
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

    private static Recognition.Partner partner(Organization o, String... relays) {
        return new Recognition.Partner(o.id(), o.publicKey().encoded(), "https://annuaire." + o.id() + ".example/",
                List.of(relays.length == 0 ? new String[] {RELAY_B} : relays));
    }

    private static Recognition.Scope scope(List<String> local, List<String> partner) {
        return new Recognition.Scope(local, partner);
    }

    private SignedDocument entry(Organization o, UUID box, String relay, long serial, String... groups) {
        return o.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), HybridKemKeyPair.generate().publicKey(), box,
                "Box " + o.id(), Map.of("iu", relay + "/iu/v1/queues/q" + serial), clock.now), clock.now, DAYS_90,
                serial, List.of(groups), "Box " + o.id());
    }

    private LocalDirectory directory() {
        return new LocalDirectory(new EntryVerifier(A.id(), A.publicKey(), serials, clock), cache, clock)
                .federate(new RecognitionVerifier(A.id(), A.publicKey(), serials), serials);
    }

    private static Recognition read(SignedDocument document) {
        return Json.read(document.payload(), Recognition.class, "reconnaissance");
    }

    @Test
    void aRecognitionFixesThePartnerKeyRelaysAndScope() {
        SignedDocument document = A.recognize(partner(B, RELAY_B + "/", "http://127.0.0.1:8444"),
                scope(List.of("imagerie-nord"), List.of("*")), clock.now, DAYS_90);
        Recognition r = read(document);
        assertThat(document.isSignedBy(A.publicKey())).isTrue();
        assertThat(r.type()).isEqualTo("recognition");
        assertThat(r.organization()).isEqualTo("grand-est");
        assertThat(r.serial()).isEqualTo(1);
        assertThat(r.partner().fingerprint()).isEqualTo(B.publicKey().fingerprint());
        assertThat(r.partner().shortCode()).isEqualTo(B.publicKey().shortCode());
        assertThat(r.partner().directory()).isEqualTo("https://annuaire.bourgogne-fc.example");
        assertThat(r.partner().relays()).containsExactly(RELAY_B, "http://127.0.0.1:8444");
        assertThat(r.partner().allows(RELAY_B + "/iu/v1/queues/x")).isTrue();
        assertThat(r.partner().allows(RELAY_B + ".pirate.example/iu/v1/queues/x")).as("préfixe trompeur").isFalse();
        assertThat(r.partner().allows(null)).isFalse();
        assertThat(r.scope().allowsLocal(List.of("imagerie-nord", "x"))).isTrue();
        assertThat(r.scope().allowsLocal(List.of("urgences"))).isFalse();
        assertThat(r.scope().allowsLocal(null)).isFalse();
        assertThat(r.scope().allowsPartner(List.of())).as("* : tous, même sans groupe").isTrue();
        assertThat(r.scope().empty()).isFalse();
        assertThat(r.statusAt(clock.now)).isEqualTo(BoxEntry.Status.ACTIVE);
        assertThat(r.statusAt(clock.now.minusSeconds(1))).isEqualTo(BoxEntry.Status.NOT_YET_VALID);
        assertThat(r.statusAt(clock.now.plus(DAYS_90))).isEqualTo(BoxEntry.Status.EXPIRED);
        assertThat(Recognition.Scope.none().empty()).isTrue();
        assertThat(new Recognition.Scope(null, List.of("a")).empty()).isTrue();

        SignedDocument renewed = A.renewRecognition(document, partner(B), scope(List.of(), List.of()), clock.now,
                DAYS_90);
        assertThat(read(renewed).serial()).isEqualTo(2);
        assertThat(read(renewed).scope().empty()).isTrue();
        SignedDocument revoked = A.revokeRecognition(renewed, clock.now, " partenariat terminé ");
        Recognition rv = read(revoked);
        assertThat(rv.serial()).isEqualTo(3);
        assertThat(rv.revocationReason()).isEqualTo("partenariat terminé");
        assertThat(rv.statusAt(clock.now)).isEqualTo(BoxEntry.Status.REVOKED);
        assertThatThrownBy(() -> A.revokeRecognition(revoked, clock.now, "x")).hasMessageContaining("déjà révoquée");
        assertThat(read(A.renewRecognition(revoked, partner(B), scope(List.of("*"), List.of("*")), clock.now,
                DAYS_90)).serial()).as("accord rétabli").isEqualTo(4);
        assertThatThrownBy(() -> A.revokeRecognition(renewed, clock.now, " ")).hasMessageContaining("Motif");
        assertThatThrownBy(() -> B.revokeRecognition(renewed, clock.now, "x")).hasMessageContaining("non signée");
        assertThatThrownBy(() -> A.renewRecognition(document, partner(C), Recognition.Scope.none(), clock.now,
                DAYS_90)).hasMessageContaining("autre clé");
        assertThatThrownBy(() -> A.recognize(partner(A), Recognition.Scope.none(), clock.now, DAYS_90))
                .hasMessageContaining("elle-même");
        assertThatThrownBy(() -> A.renewRecognition(document, partner(A), Recognition.Scope.none(), clock.now,
                DAYS_90)).hasMessageContaining("elle-même");
    }

    @Test
    void invalidRecognitionsAreRefused() {
        HybridPublicKey.Encoded key = B.publicKey().encoded();
        assertThatThrownBy(() -> new Recognition.Partner("Pas Valide", key, "https://a.example", List.of(RELAY_B)))
                .hasMessageContaining("Identifiant");
        assertThatThrownBy(() -> new Recognition.Partner("b", null, "https://a.example", List.of(RELAY_B)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Recognition.Partner("b", key, null, List.of(RELAY_B))).hasMessageContaining(
                "manquant");
        assertThatThrownBy(() -> new Recognition.Partner("b", key, "ftp://a.example", List.of(RELAY_B)))
                .hasMessageContaining("http(s)");
        assertThatThrownBy(() -> new Recognition.Partner("b", key, "https://a.example?x=1", List.of(RELAY_B)))
                .hasMessageContaining("http(s)");
        assertThatThrownBy(() -> new Recognition.Partner("b", key, "https:// a", List.of(RELAY_B)))
                .hasMessageContaining("invalide");
        assertThatThrownBy(() -> new Recognition.Partner("b", key, "https://a.example", List.of()))
                .hasMessageContaining("de 1 à");
        assertThatThrownBy(() -> new Recognition.Partner("b", key, "https://a.example", null))
                .hasMessageContaining("de 1 à");
        assertThatThrownBy(() -> new Recognition.Partner("b", key, "https://a.example", List.of(RELAY_B, RELAY_B
                + "/"))).hasMessageContaining("double");
        assertThatThrownBy(() -> scope(List.of("*", "a"), List.of())).hasMessageContaining("ne se combine");
        assertThatThrownBy(() -> scope(List.of("Pas Valide"), List.of())).hasMessageContaining("groupe");
        Recognition.Partner p = partner(B);
        Instant now = clock.now;
        assertThatThrownBy(() -> new Recognition("IU1", "autre", "grand-est", p, null, 1, now, now.plusSeconds(1),
                false, null, null)).hasMessageContaining("type");
        assertThatThrownBy(() -> new Recognition("IU1", "recognition", "grand-est", p, null, 0, now,
                now.plusSeconds(1), false, null, null)).hasMessageContaining("commence à 1");
        assertThatThrownBy(() -> new Recognition("IU1", "recognition", "grand-est", p, null, 1, now, now, false, null,
                null)).hasMessageContaining("antérieure");
        assertThatThrownBy(() -> new Recognition("IU1", "recognition", "grand-est", p, null, 1, now,
                now.plusSeconds(1), true, now, "x".repeat(501))).hasMessageContaining("trop long");
        assertThat(new Recognition("IU1", "recognition", "grand-est", p, null, 1, now, now.plusSeconds(1), false,
                null, null).scope().empty()).isTrue();
    }

    @Test
    void recognitionsAreVerifiedWithTheResponsibleKeyWithoutRollback() {
        RecognitionVerifier verifier = new RecognitionVerifier(A.id(), A.publicKey(), serials);
        SignedDocument first = A.recognize(partner(B), scope(List.of("*"), List.of("*")), clock.now, DAYS_90);
        SignedDocument second = A.renewRecognition(first, partner(B), scope(List.of("*"), List.of("*")), clock.now,
                DAYS_90);
        assertThat(verifier.verify(second).serial()).isEqualTo(2);
        assertThatThrownBy(() -> verifier.verify(first)).hasMessageContaining("Retour en arrière");
        SignedDocument twin = A.renewRecognition(first, partner(B, "https://autre.example"), Recognition.Scope.none(),
                clock.now, DAYS_90);
        assertThatThrownBy(() -> verifier.verify(twin)).hasMessageContaining("différente");
        assertThat(verifier.verify(second).serial()).as("même document : accepté").isEqualTo(2);

        assertThatThrownBy(() -> verifier.verify(B.recognize(partner(C), Recognition.Scope.none(), clock.now,
                DAYS_90))).hasMessageContaining("non signée");
        SignedDocument otherOrg = SignedDocument.sign(first.payload().replace("\"grand-est\"", "\"autre\""), A_KEYS);
        assertThatThrownBy(() -> verifier.verify(otherOrg)).hasMessageContaining("autre organisation");
        SignedDocument self = SignedDocument.sign(first.payload().replace(B.publicKey().encoded().ed25519(),
                A.publicKey().encoded().ed25519()).replace(B.publicKey().encoded().mlDsa65(),
                A.publicKey().encoded().mlDsa65()), A_KEYS);
        assertThatThrownBy(() -> verifier.verify(self)).hasMessageContaining("elle-même");
        SignedDocument future = SignedDocument.sign(first.payload().replace("\"IU1\"", "\"IU2\""), A_KEYS);
        assertThatThrownBy(() -> verifier.verify(future)).isInstanceOf(DirectoryException.class);
        // Champ inconnu (version ultérieure) : ignoré, la signature couvrant les octets exacts.
        SignedDocument extended = SignedDocument.sign(first.payload().replaceFirst("\\{", "{\"futur\":1,"), A_KEYS);
        assertThat(new RecognitionVerifier(A.id(), A.publicKey(), SerialMemory.inMemory()).verify(extended).serial())
                .isEqualTo(1);
        assertThat(RecognitionVerifier.isRecognition(first)).isTrue();
        assertThat(RecognitionVerifier.isRecognition(entry(A, UUID.randomUUID(), RELAY_B, 1))).isFalse();
        assertThat(RecognitionVerifier.isRecognition(SignedDocument.sign("pas du json", A_KEYS))).isFalse();
        assertThat(RecognitionVerifier.key("ab").version()).isEqualTo(3);
    }

    @Test
    void partnerEntriesAreUsableOnlyWithinAnActiveAgreement() {
        LocalDirectory directory = directory();
        assertThat(directory.federated()).isTrue();
        UUID local = UUID.randomUUID();
        UUID nord = UUID.randomUUID();
        UUID sud = UUID.randomUUID();
        UUID outside = UUID.randomUUID();
        directory.synchronize(() -> List.of(entry(A, local, "https://relais.grand-est.example", 1, "imagerie-nord")));
        String fpB = B.publicKey().fingerprint();
        assertThat(directory.synchronizePartner(fpB, List::of).error()).isEqualTo("Organisation non reconnue");

        SignedDocument recognition = A.recognize(partner(B), scope(List.of("imagerie-nord"), List.of("neuro")),
                clock.now, DAYS_90);
        LocalDirectory.SyncStatus rs = directory.synchronizeRecognitions(() -> List.of(recognition,
                B.recognize(partner(C), Recognition.Scope.none(), clock.now, DAYS_90)));
        assertThat(rs.accepted()).isEqualTo(1);
        assertThat(rs.rejected()).isEqualTo(1);
        assertThat(directory.recognitionSync()).isEqualTo(rs);
        assertThat(directory.activeRecognitions()).hasSize(1);

        LocalDirectory.SyncStatus ps = directory.synchronizePartner(fpB, () -> List.of(
                entry(B, nord, RELAY_B, 1, "neuro"),
                entry(B, sud, RELAY_B, 1, "urgences"),
                entry(B, outside, "https://relais.pirate.example", 1, "neuro"),
                entry(C, UUID.randomUUID(), RELAY_B, 1, "neuro")));
        assertThat(ps.accepted()).isEqualTo(3);
        assertThat(ps.rejected()).as("signée par une autre clé").isEqualTo(1);
        assertThat(directory.partnerSync(fpB)).isEqualTo(ps);

        assertThat(directory.entry(nord)).isPresent();
        assertThat(directory.status(nord)).isEqualTo(BoxEntry.Status.ACTIVE);
        assertThat(directory.entry(sud)).as("hors périmètre").isEmpty();
        assertThat(directory.entry(outside)).as("relais non autorisé").isEmpty();
        assertThat(directory.entries()).extracting(BoxEntry::boxId).containsExactlyInAnyOrder(local, nord);
        assertThat(directory.partnerEntries(fpB)).hasSize(3);
        assertThat(directory.isLocal(local)).isTrue();
        assertThat(directory.isLocal(nord)).isFalse();
        assertThat(directory.partnerOf(nord)).hasValueSatisfying(r -> assertThat(r.partner().organization())
                .isEqualTo("bourgogne-fc"));
        assertThat(directory.partnerOf(local)).isEmpty();

        UUID otherLocal = UUID.randomUUID();
        directory.synchronize(() -> List.of(entry(A, otherLocal, "https://relais.grand-est.example", 1, "urgences")));
        assertThat(directory.exchangeAllowed(local, nord)).isTrue();
        assertThat(directory.exchangeAllowed(local, otherLocal)).isTrue();
        assertThat(directory.exchangeRefusal(local, nord)).isEmpty();
        assertThat(directory.exchangeRefusal(otherLocal, nord)).hasValueSatisfying(m -> assertThat(m)
                .contains("pas dans les groupes autorisés"));
        assertThat(directory.exchangeAllowed(UUID.randomUUID(), nord)).as("box locale inconnue").isFalse();
        assertThat(directory.exchangeRefusal(local, sud)).hasValueSatisfying(m -> assertThat(m)
                .contains("hors du périmètre"));
        assertThat(directory.exchangeRefusal(local, outside)).hasValueSatisfying(m -> assertThat(m)
                .contains("relais non autorisé"));
        assertThat(directory.exchangeRefusal(local, UUID.randomUUID())).hasValueSatisfying(m -> assertThat(m)
                .contains("absente"));

        // Accord expiré, puis révoqué : plus aucune entrée du partenaire n'est utilisable.
        clock.now = clock.now.plus(DAYS_90);
        assertThat(directory.entry(nord)).isEmpty();
        assertThat(directory.activeRecognitions()).isEmpty();
        assertThat(directory.exchangeRefusal(local, nord)).hasValueSatisfying(m -> assertThat(m).contains("expiré"));
        SignedDocument revoked = A.revokeRecognition(recognition, clock.now, "fin");
        directory.synchronizeRecognitions(() -> List.of(revoked));
        assertThat(directory.exchangeRefusal(local, nord)).hasValueSatisfying(m -> assertThat(m).contains("révoqué"));
        assertThat(directory.recognitions()).hasSize(1);
    }

    @Test
    void aBoxIdentifierBelongsToOneOrganizationOnly() {
        LocalDirectory directory = directory();
        String fpB = B.publicKey().fingerprint();
        String fpC = C.publicKey().fingerprint();
        directory.synchronizeRecognitions(() -> List.of(
                A.recognize(partner(B), scope(List.of("*"), List.of("*")), clock.now, DAYS_90),
                A.recognize(partner(C), scope(List.of("*"), List.of("*")), clock.now, DAYS_90)));
        UUID local = UUID.randomUUID();
        UUID shared = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        directory.synchronize(() -> List.of(entry(A, local, "https://relais.grand-est.example", 1)));

        // B revendique l'identifiant d'une box locale, avec un numéro très élevé : écartée, la box locale intacte.
        directory.synchronizePartner(fpB, () -> List.of(entry(B, local, RELAY_B, 1000), entry(B, shared, RELAY_B, 1),
                entry(B, later, RELAY_B, 5)));
        assertThat(directory.isLocal(local)).isTrue();
        assertThat(directory.entry(local).orElseThrow().organization()).isEqualTo("grand-est");
        // C revendique un identifiant déjà tenu par B : écartée.
        assertThat(directory.synchronizePartner(fpC, () -> List.of(entry(C, shared, RELAY_B, 1))).rejected())
                .isEqualTo(1);
        assertThat(directory.entry(shared).orElseThrow().organization()).isEqualTo("bourgogne-fc");
        // L'organisation locale publie plus tard une box sous un identifiant tenu par B : la locale l'emporte,
        // sans être gênée par le numéro élevé de B.
        directory.synchronize(() -> List.of(entry(A, later, "https://relais.grand-est.example", 1)));
        assertThat(directory.entry(later).orElseThrow().organization()).isEqualTo("grand-est");
        assertThat(directory.partnerEntries(fpB)).extracting(BoxEntry::boxId).containsExactly(shared);
        assertThat(directory.collisions()).extracting(LocalDirectory.Collision::boxId, LocalDirectory.Collision::claimant,
                LocalDirectory.Collision::holder).containsExactly(
                org.assertj.core.groups.Tuple.tuple(local, fpB, LocalDirectory.LOCAL),
                org.assertj.core.groups.Tuple.tuple(shared, fpC, fpB),
                org.assertj.core.groups.Tuple.tuple(later, fpB, LocalDirectory.LOCAL));
        SignedDocument claim = entry(C, local, RELAY_B, 1);
        for (int i = 0; i < LocalDirectory.MAX_COLLISIONS + 5; i++) {
            directory.synchronizePartner(fpC, () -> List.of(claim));
        }
        assertThat(directory.collisions()).hasSize(LocalDirectory.MAX_COLLISIONS);
    }

    @Test
    void theCacheKeepsRecognitionsAndPartnerEntriesAcrossRestarts() {
        LocalDirectory first = directory();
        String fpB = B.publicKey().fingerprint();
        UUID local = UUID.randomUUID();
        UUID nord = UUID.randomUUID();
        first.synchronize(() -> List.of(entry(A, local, "https://relais.grand-est.example", 1)));
        first.synchronizeRecognitions(() -> List.of(A.recognize(partner(B), scope(List.of("*"), List.of("*")),
                clock.now, DAYS_90)));
        first.synchronizePartner(fpB, () -> List.of(entry(B, nord, RELAY_B, 1)));
        // Documents en cache qu'aucune organisation reconnue n'a signés, ou falsifiés.
        cache.documents.put(UUID.randomUUID(), entry(C, UUID.randomUUID(), RELAY_B, 1));
        cache.documents.put(UUID.randomUUID(), SignedDocument.sign(cache.documents.get(RecognitionVerifier.key(fpB))
                .payload(), C_KEYS));

        LocalDirectory restarted = new LocalDirectory(new EntryVerifier(A.id(), A.publicKey(),
                SerialMemory.inMemory(), clock), cache, clock).federate(new RecognitionVerifier(A.id(), A.publicKey(),
                SerialMemory.inMemory()), SerialMemory.inMemory());
        restarted.load();
        assertThat(restarted.entry(local)).isPresent();
        assertThat(restarted.entry(nord)).isPresent();
        assertThat(restarted.recognitions()).hasSize(1);

        // Sans fédération : les reconnaissances et entrées de partenaires en cache sont ignorées.
        LocalDirectory plain = new LocalDirectory(new EntryVerifier(A.id(), A.publicKey(), SerialMemory.inMemory(),
                clock), cache, clock);
        plain.load();
        assertThat(plain.federated()).isFalse();
        assertThat(plain.entries()).extracting(BoxEntry::boxId).containsExactly(local);
        assertThatThrownBy(() -> plain.synchronizeRecognitions(List::of)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> plain.synchronizePartner(fpB, List::of)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unreadableDirectoriesAreOnlyNoted() {
        LocalDirectory directory = directory();
        directory.synchronizeRecognitions(() -> List.of(A.recognize(partner(B), scope(List.of("*"), List.of("*")),
                clock.now, DAYS_90)));
        String fpB = B.publicKey().fingerprint();
        assertThat(directory.synchronizeRecognitions(() -> {
            throw new DirectoryException("injoignable");
        }).error()).isEqualTo("injoignable");
        assertThat(directory.synchronizePartner(fpB, () -> {
            throw new DirectoryException("partenaire injoignable");
        }).error()).isEqualTo("partenaire injoignable");
        assertThat(directory.recognitions()).hasSize(1);
        assertThat(directory.partnerSync("inconnu")).isNull();
    }

    @Test
    void scopedSerialMemoriesDoNotInterfere() {
        UUID box = UUID.randomUUID();
        SerialMemory partner = serials.scoped("b");
        partner.record(box, 1000, "x");
        assertThat(partner.digest(box)).hasValue("x");
        partner.record(box, 1001);
        assertThat(partner.highest(box)).hasValue(1001);
        assertThat(serials.highest(box)).isEmpty();
        assertThat(serials.scoped("c").highest(box)).isEmpty();
    }

    @Test
    void theClientReadsRecognitionsAndPartnerEntries() throws Exception {
        SignedDocument recognition = A.recognize(partner(B), scope(List.of("*"), List.of("*")), clock.now, DAYS_90);
        SignedDocument nord = entry(B, UUID.randomUUID(), RELAY_B, 1);
        String fpB = B.publicKey().fingerprint();
        int[] status = {200};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/recognitions") ? "{\"organization\":\"grand-est\",\"recognitions\":["
                    + recognition.toJson() + ",null]}" : "{\"organization\":\"bourgogne-fc\",\"entries\":["
                    + nord.toJson() + "]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status[0], bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            DirectoryClient client = new DirectoryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/"), Duration.ofSeconds(5));
            assertThat(client.fetchRecognitions()).containsExactly(recognition);
            assertThat(client.fetchPartnerEntries(fpB)).containsExactly(nord);
            assertThatThrownBy(() -> client.fetchPartnerEntries("../entries")).hasMessageContaining("mal formée");
            status[0] = 404;
            assertThat(client.fetchRecognitions()).as("annuaire antérieur à la fédération").isEmpty();
            status[0] = 500;
            assertThatThrownBy(client::fetchRecognitions).hasMessageContaining("500");
            status[0] = 404;
            assertThatThrownBy(() -> client.fetchPartnerEntries(fpB)).hasMessageContaining("404");
        } finally {
            server.stop(0);
        }
    }
}
