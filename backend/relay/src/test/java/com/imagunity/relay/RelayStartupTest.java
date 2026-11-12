package com.imagunity.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.SignedDocument;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;

class RelayStartupTest {

    private static RelayProperties properties(URI url, Path key, String code) {
        return new RelayProperties(Path.of("x"), Duration.ofHours(72), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofSeconds(30), DataSize.ofGigabytes(1), DataSize.ofMegabytes(1),
                new RelayProperties.Directory(url, key, code, Duration.ofMinutes(5)));
    }

    @Test
    void relayNeedsItsDirectoryAndAConfirmedOrganizationKey() throws Exception {
        RelayFixture.start();
        RelayConfiguration configuration = new RelayConfiguration();
        URI url = URI.create(RelayFixture.directoryUrl());

        assertThatThrownBy(() -> configuration.organizationKey(properties(null, RelayFixture.organizationKey, "x")))
                .hasMessageContaining("IMAGUNITY_RELAY_DIRECTORY_URL");
        assertThatThrownBy(() -> configuration.organizationKey(properties(url, null, "x")))
                .hasMessageContaining("IMAGUNITY_RELAY_ORGANIZATION_KEY");
        assertThatThrownBy(() -> configuration.organizationKey(properties(url, RelayFixture.organizationKey,
                "AAAA-BBBB"))).hasMessageContaining(RelayFixture.ORG_KEYS.publicKey().shortCode());
        assertThatThrownBy(() -> configuration.organizationKey(properties(url, RelayFixture.organizationKey, null)))
                .hasMessageContaining("IMAGUNITY_RELAY_ORGANIZATION_CODE");
        assertThat(configuration.organizationKey(properties(url, RelayFixture.organizationKey,
                RelayFixture.ORG_KEYS.publicKey().shortCode())).organization()).isEqualTo("chu-exemple");
    }

    @Test
    void directoryStoreKeepsTheHighestSerialAndTheLatestEntry() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:annuaire-relais-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE TABLE directory_entry (box_id UUID PRIMARY KEY, serial BIGINT NOT NULL, document TEXT NOT NULL,"
                + " fetched_at TIMESTAMP WITH TIME ZONE NOT NULL)").update();
        jdbc.sql("CREATE TABLE directory_serial (box_id UUID PRIMARY KEY, serial BIGINT NOT NULL)").update();
        RelayDirectoryStore store = new RelayDirectoryStore(dataSource,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        UUID box = UUID.randomUUID();
        SignedDocument first = SignedDocument.sign("{\"n\":1}", HybridKeyPair.generate());
        SignedDocument second = SignedDocument.sign("{\"n\":2}", HybridKeyPair.generate());

        assertThat(store.highest(box)).isEmpty();
        store.record(box, 2);
        store.record(box, 1);
        assertThat(store.highest(box)).hasValue(2);
        store.save(box, 1, first, Instant.now());
        store.save(box, 2, second, Instant.now());
        assertThat(store.entries()).containsExactly(second);
    }

    @Test
    void mainStartsTheRelay() throws Exception {
        RelayFixture.start();
        // Contexte refermé par le crochet d'arrêt de Spring à la fin de la JVM de test.
        RelayApplication.main(new String[] {
            "--server.port=0",
            "--spring.datasource.url=jdbc:h2:mem:relais-main;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "--spring.jmx.enabled=false",
            "--imagunity.relay.storage-dir=./target/morceaux-main",
            "--imagunity.relay.directory.url=" + RelayFixture.directoryUrl(),
            "--imagunity.relay.directory.organization-key=" + RelayFixture.organizationKey,
            "--imagunity.relay.directory.organization-code=" + RelayFixture.ORG_KEYS.publicKey().shortCode()
        });
    }

    @Test
    void chunkBodiesAreCutBeyondTheAnnouncedSize() throws Exception {
        ChunkFiles files = new ChunkFiles(java.nio.file.Files.createTempDirectory("morceaux"));
        java.io.InputStream oneByteAtATime = new java.io.InputStream() {
            private int left = 3;

            @Override
            public int read() {
                return left-- > 0 ? 7 : -1;
            }

            @Override
            public long transferTo(java.io.OutputStream out) throws java.io.IOException {
                long n = 0;
                for (int b; (b = read()) != -1; n++) {
                    out.write(b);
                }
                return n;
            }
        };
        assertThatThrownBy(() -> files.receive(oneByteAtATime, 1)).isInstanceOf(RelayException.class)
                .hasMessageContaining("plus gros");
    }
}
