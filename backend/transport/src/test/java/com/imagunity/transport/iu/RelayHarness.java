package com.imagunity.transport.iu;

import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SignedDocument;
import com.imagunity.relay.RelayApplication;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Vrai relais IU-T démarré en mémoire (H2) pour les tests du client, avec un annuaire simulé : deux box enrôlées,
 * A et B, avec leurs clés de chiffrement (données synthétiques). Démarré une fois pour toute la JVM de test.
 */
final class RelayHarness {

    static final HybridKeyPair ORG = HybridKeyPair.generate();
    static final HybridKeyPair A_KEYS = HybridKeyPair.generate();
    static final HybridKemKeyPair A_KEM = HybridKemKeyPair.generate();
    static final UUID A = UUID.randomUUID();
    static final HybridKeyPair B_KEYS = HybridKeyPair.generate();
    static final HybridKemKeyPair B_KEM = HybridKemKeyPair.generate();
    static final UUID B = UUID.randomUUID();
    static Path organizationKey;
    static URI relayUrl;
    static URI directoryUrl;
    private static ConfigurableApplicationContext relay;

    private RelayHarness() {
    }

    static synchronized void start() throws Exception {
        if (relay != null) {
            return;
        }
        Organization organization = new Organization("chu-exemple", ORG);
        Instant now = Instant.now();
        List<SignedDocument> entries = List.of(
                organization.enroll(EnrollmentRequest.create(A_KEYS, A_KEM.publicKey(), A, "Site A", Map.of(), now),
                        now.minusSeconds(60), Duration.ofDays(30), 1),
                organization.enroll(EnrollmentRequest.create(B_KEYS, B_KEM.publicKey(), B, "Site B", Map.of(), now),
                        now.minusSeconds(60), Duration.ofDays(30), 1));
        HttpServer directory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        directory.createContext("/directory/v1/entries", exchange -> {
            byte[] body = ("{\"organization\":\"chu-exemple\",\"entries\":["
                    + String.join(",", entries.stream().map(SignedDocument::toJson).toList()) + "]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        directory.start();
        directoryUrl = URI.create("http://127.0.0.1:" + directory.getAddress().getPort());
        organizationKey = Files.createTempFile("organisation", ".pub.json");
        KeyFiles.writeOrganizationPublic(organizationKey, "chu-exemple", ORG.publicKey());
        relay = relay("relais-client");
        relayUrl = URI.create("http://127.0.0.1:" + relay.getEnvironment().getProperty("local.server.port"));
        // Contexte refermé par le crochet d'arrêt de Spring à la fin de la JVM de test.
    }

    /**
     * Relais supplémentaire, indépendant (sa base, son stockage), sur le même annuaire : pour les essais à plusieurs
     * relais. À refermer par l'essai (arrêt simulé d'un relais).
     */
    static ConfigurableApplicationContext relay(String name) throws Exception {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(RelayApplication.class).run(
                "--server.port=0", "--spring.jmx.enabled=false",
                "--spring.datasource.url=jdbc:h2:mem:" + name + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "--spring.datasource.username=sa",
                "--imagunity.relay.storage-dir=" + Files.createTempDirectory("relais"),
                "--imagunity.relay.min-free-space=1MB",
                "--imagunity.relay.max-wait=2s", "--imagunity.relay.max-queues-per-box=100",
                "--imagunity.relay.directory.url=" + directoryUrl,
                "--imagunity.relay.directory.organization-key=" + organizationKey,
                "--imagunity.relay.directory.organization-code=" + ORG.publicKey().shortCode());
        LocalDirectory local = context.getBean(LocalDirectory.class);
        for (int i = 0; i < 100 && (local.entry(A).isEmpty() || local.entry(B).isEmpty()); i++) {
            Thread.sleep(50);
        }
        return context;
    }

    static URI url(ConfigurableApplicationContext relay) {
        return URI.create("http://127.0.0.1:" + relay.getEnvironment().getProperty("local.server.port"));
    }
}
