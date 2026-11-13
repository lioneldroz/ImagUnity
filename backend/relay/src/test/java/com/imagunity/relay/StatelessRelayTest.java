package com.imagunity.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.Organization;
import com.imagunity.directory.SignedDocument;
import com.imagunity.protocol.SignedRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

/**
 * Relais sans état (spécification § 6.8) : ce que plusieurs instances partagent (anti-rejeu, bail de purge, annuaire,
 * stockage S3) est en base ou sur le stockage, et vaut pour toutes. Données synthétiques.
 */
class StatelessRelayTest {

    @TempDir
    Path dir;

    /** Horloge réglable. */
    private static final class MovableClock extends Clock {
        final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-13T10:00:00Z"));

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
            return now.get();
        }
    }

    private static DataSource database() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:sans-etat-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(dataSource).load().migrate();
        return dataSource;
    }

    private static SignedRequest request(Instant at, String id) {
        return new SignedRequest("GET", "/iu/v1/chunks/x", at.getEpochSecond(), id,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
    }

    @Test
    void aRequestReplayedToAnotherInstanceIsRefused() {
        DataSource shared = database();
        MovableClock clock = new MovableClock();
        ReplayStore first = new ReplayStore(shared, clock);
        ReplayStore second = new ReplayStore(shared, clock);
        String id = "AAAAAAAAAAAAAAAAAAAAAA";
        assertThat(first.admit(request(clock.instant(), id))).isEmpty();
        assertThat(second.admit(request(clock.instant(), id))).contains("requête rejouée");
        assertThat(first.admit(request(clock.instant().minusSeconds(600), "BBBBBBBBBBBBBBBBBBBBBB")))
                .get().asString().contains("horodatage");
        assertThat(first.purge()).isZero();
        clock.now.set(clock.instant().plus(Duration.ofMinutes(6)));
        assertThat(second.purge()).as("sorti de la fenêtre : oublié").isEqualTo(1);
    }

    @Test
    void onlyOneInstanceHoldsTheLeaseUntilItExpires() {
        DataSource shared = database();
        MovableClock clock = new MovableClock();
        RelayLease a = new RelayLease(shared, clock, "relais-a");
        RelayLease b = new RelayLease(shared, clock, "relais-b");
        assertThat(a.holder()).isEqualTo("relais-a");
        assertThat(a.acquire("purge", Duration.ofMinutes(3))).isTrue();
        assertThat(b.acquire("purge", Duration.ofMinutes(3))).as("détenu par a").isFalse();
        assertThat(a.acquire("purge", Duration.ofMinutes(3))).as("renouvelé").isTrue();
        clock.now.set(clock.instant().plus(Duration.ofMinutes(4)));
        assertThat(b.acquire("purge", Duration.ofMinutes(3))).as("a ne renouvelle plus : repris").isTrue();
        assertThat(a.acquire("purge", Duration.ofMinutes(3))).isFalse();
    }

    @Test
    void aLateInstanceNeverReplacesANewerDirectoryEntry() throws Exception {
        DataSource shared = database();
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(shared));
        RelayDirectoryStore one = new RelayDirectoryStore(shared, tx);
        RelayDirectoryStore two = new RelayDirectoryStore(shared, tx);
        Organization organization = new Organization("chu-exemple", RelayFixture.ORG_KEYS);
        Instant now = Instant.now();
        UUID box = RelayFixture.ACTIVE;
        SignedDocument v1 = organization.enroll(com.imagunity.directory.EnrollmentRequest.create(
                RelayFixture.ACTIVE_KEYS, box, "Site", java.util.Map.of(), now), now, Duration.ofDays(1), 1);
        SignedDocument v2 = organization.enroll(com.imagunity.directory.EnrollmentRequest.create(
                RelayFixture.ACTIVE_KEYS, box, "Site", java.util.Map.of(), now), now, Duration.ofDays(1), 2);
        one.save(box, 2, v2, now);
        two.save(box, 1, v1, now);
        assertThat(one.entries()).singleElement().satisfies(d -> assertThat(d.toJson()).isEqualTo(v2.toJson()));
        one.record(box, 2);
        two.record(box, 1);
        two.record(UUID.randomUUID(), 1);
        assertThat(one.highest(box)).hasValue(2);
    }

    @Test
    void chunksGoToAnS3ServiceSharedByTheInstances() throws Exception {
        try (FakeS3 service = new FakeS3();
             S3ChunkContents s3 = new S3ChunkContents(S3ChunkContents.client(service.url(), "us-east-1", "essai",
                     "secret-synthetique", true), "imagunity-relais", "morceaux/")) {
            s3.ensureBucket();
            assertThat(service.buckets).containsExactly("imagunity-relais");
            s3.ensureBucket(); // déjà là
            ChunkFiles files = new ChunkFiles(dir.resolve("tmp"));
            byte[] content = "contenu chiffré synthétique".getBytes(StandardCharsets.UTF_8);
            ChunkFiles.Received received = files.receive(new java.io.ByteArrayInputStream(content), 1000);
            s3.commit(received, "expediteur-1");
            assertThat(service.objects).containsKey("imagunity-relais/morceaux/expediteur-1");
            var resource = s3.open("expediteur-1", content.length);
            assertThat(resource.exists()).isTrue();
            assertThat(resource.contentLength()).isEqualTo(content.length);
            assertThat(resource.getDescription()).contains("S3");
            try (var in = resource.getInputStream()) {
                assertThat(in.readAllBytes()).isEqualTo(content);
            }
            assertThat(s3.usableSpace()).isEmpty();
            assertThat(s3.toString()).contains("imagunity-relais");
            s3.delete("expediteur-1");
            assertThat(service.objects).isEmpty();

            service.failWith = 503;
            assertThatThrownBy(() -> s3.commit(received, "expediteur-2")).isInstanceOf(IOException.class)
                    .hasMessageContaining("Dépôt");
            assertThatThrownBy(() -> s3.open("expediteur-2", 1).getInputStream()).isInstanceOf(IOException.class)
                    .hasMessageContaining("Lecture");
            assertThatThrownBy(() -> s3.delete("expediteur-2")).isInstanceOf(IOException.class);
            assertThatThrownBy(s3::ensureBucket).isInstanceOf(IOException.class).hasMessageContaining("inaccessible");
            service.failWith = 0;
        }
        try (S3ChunkContents unreachable = new S3ChunkContents(S3ChunkContents.client(URI.create(
                "http://127.0.0.1:1"), "us-east-1", "essai", "secret", true), "b", null)) {
            assertThatThrownBy(unreachable::ensureBucket).isInstanceOf(IOException.class)
                    .hasMessageContaining("injoignable");
            assertThat(unreachable.key("x")).isEqualTo("x");
        }
    }

    @Test
    void theStorageIsChosenByConfiguration() throws Exception {
        RelayConfiguration configuration = new RelayConfiguration();
        ChunkFiles files = new ChunkFiles(dir.resolve("disque"));
        assertThat(configuration.chunkContents(files, properties(storage("disk", null, null, null))))
                .isSameAs(files);
        assertThatThrownBy(() -> configuration.chunkContents(files, properties(storage("bande", null, null, null))))
                .hasMessageContaining("inconnu");
        assertThatThrownBy(() -> configuration.chunkContents(files, properties(storage("s3", null, null, null))))
                .hasMessageContaining("sans compte");
        try (FakeS3 service = new FakeS3()) {
            ChunkContents s3 = configuration.chunkContents(files, properties(storage("S3", service.url(), "essai",
                    "secret")));
            assertThat(s3).isInstanceOf(S3ChunkContents.class);
            ((S3ChunkContents) s3).close();
        }
        assertThat(properties(storage("disk", null, null, null)).instance()).isNotBlank();
        assertThat(new RelayProperties(dir, Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofSeconds(1), DataSize.ofMegabytes(1), DataSize.ofMegabytes(1), 1, null,
                storage("disk", null, null, null), " relais-a ").instance()).isEqualTo("relais-a");
        assertThat(storage("s3", null, "essai", "secret").toString()).doesNotContain("secret]")
                .doesNotContain("essai");
    }

    private static RelayProperties.Storage storage(String type, URI endpoint, String access, String secret) {
        return new RelayProperties.Storage(type, endpoint, "us-east-1", "imagunity-relais", "morceaux/", access,
                secret, true);
    }

    private RelayProperties properties(RelayProperties.Storage storage) {
        return new RelayProperties(dir, Duration.ofHours(1), Duration.ofHours(1), Duration.ofHours(1),
                Duration.ofSeconds(1), DataSize.ofMegabytes(1), DataSize.ofMegabytes(1), 1, null, storage, null);
    }
}
