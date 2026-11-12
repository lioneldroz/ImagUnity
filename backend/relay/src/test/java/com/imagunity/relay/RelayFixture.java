package com.imagunity.relay;

import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.KeyFiles;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SignedDocument;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Organisation d'essai et service d'annuaire simulé : une box active, une box révoquée (données synthétiques).
 */
final class RelayFixture {

    static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    static final Organization ORGANIZATION = new Organization("chu-exemple", ORG_KEYS);
    static final HybridKeyPair ACTIVE_KEYS = HybridKeyPair.generate();
    static final UUID ACTIVE = UUID.randomUUID();
    static final HybridKeyPair REVOKED_KEYS = HybridKeyPair.generate();
    static final UUID REVOKED = UUID.randomUUID();
    static final List<SignedDocument> PUBLISHED = new ArrayList<>();
    static HttpServer directory;
    static Path organizationKey;

    private RelayFixture() {
    }

    static synchronized void start() throws IOException {
        if (directory != null) {
            return;
        }
        Instant now = Instant.now();
        PUBLISHED.add(ORGANIZATION.enroll(EnrollmentRequest.create(ACTIVE_KEYS, ACTIVE, "Site A", Map.of(), now),
                now.minusSeconds(60), Duration.ofDays(30), 1));
        SignedDocument revoked = ORGANIZATION.enroll(EnrollmentRequest.create(REVOKED_KEYS, REVOKED, "Site volé",
                Map.of(), now), now.minusSeconds(60), Duration.ofDays(30), 1);
        PUBLISHED.add(ORGANIZATION.revoke(revoked, now.minusSeconds(30), "Box volée"));
        directory = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        directory.createContext("/directory/v1/entries", exchange -> {
            StringBuilder json = new StringBuilder("{\"organization\":\"chu-exemple\",\"entries\":[");
            for (int i = 0; i < PUBLISHED.size(); i++) {
                json.append(i == 0 ? "" : ",").append(PUBLISHED.get(i).toJson());
            }
            byte[] body = json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        directory.start();
        organizationKey = Files.createTempFile("organisation", ".pub.json");
        KeyFiles.writeOrganizationPublic(organizationKey, "chu-exemple", ORG_KEYS.publicKey());
    }

    static String directoryUrl() {
        return "http://127.0.0.1:" + directory.getAddress().getPort();
    }
}
