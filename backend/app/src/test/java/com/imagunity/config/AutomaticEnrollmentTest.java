package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.EnrollmentCode;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.EnrollmentSubmission;
import com.imagunity.directory.EntryCache;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import com.imagunity.orchestration.BoxIdentity;
import com.imagunity.transport.DisabledTransport;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/** Envoi automatique de la demande d'enrôlement par la box (données synthétiques). */
class AutomaticEnrollmentTest {

    private static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    private final Clock clock = Clock.systemUTC();
    private final BoxIdentity me = new BoxIdentity(UUID.randomUUID(), HybridKeyPair.generate(),
            HybridKemKeyPair.generate());
    private final List<SignedDocument> cached = new CopyOnWriteArrayList<>();
    private final LocalDirectory directory = new LocalDirectory(new EntryVerifier("chu-exemple", ORG_KEYS.publicKey(),
            SerialMemory.inMemory(), clock), new EntryCache() {
                @Override
                public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
                }

                @Override
                public List<SignedDocument> entries() {
                    return List.copyOf(cached);
                }
            }, clock);

    @Test
    void theBoxSendsItsRequestUntilItIsInTheDirectory() throws Exception {
        String code = EnrollmentCode.generate();
        int[] status = {202};
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/enrollment-requests", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status[0], -1);
            exchange.close();
        });
        server.start();
        DirectoryClient client = new DirectoryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(5));
        com.imagunity.api.EnrollmentProgress progress = new com.imagunity.api.EnrollmentProgress("chu-exemple",
                ORG_KEYS.publicKey().shortCode(), "http://annuaire", true);
        AutomaticEnrollment enrollment = new AutomaticEnrollment(directory, client, me, new DisabledTransport(),
                "CHU Exemple — imagerie Nord", code.toLowerCase(), clock, progress);
        try {
            assertThat(progress.view().submittedAt()).as("pas encore envoyée").isNull();
            assertThat(enrollment.submitIfNeeded()).isTrue();
            java.time.Instant first = progress.view().submittedAt();
            assertThat(first).isNotNull();
            assertThat(progress.view().error()).isNull();
            assertThat(progress.view().organization()).isEqualTo("chu-exemple");
            assertThat(progress.view().codeProvided()).isTrue();
            EnrollmentSubmission sent = EnrollmentSubmission.fromJson(received.getFirst());
            assertThat(sent.provenBy(code)).as("preuve du code jointe").isTrue();
            EnrollmentRequest request = EnrollmentRequest.verify(sent.request());
            assertThat(request.boxId()).isEqualTo(me.boxId());
            assertThat(request.displayName()).isEqualTo("CHU Exemple — imagerie Nord");
            assertThat(enrollment.submitIfNeeded()).as("renvoyée tant qu'elle n'est pas enrôlée").isTrue();

            status[0] = 409;
            assertThat(enrollment.submitIfNeeded()).isFalse();
            assertThat(progress.view().error()).contains("écartée par l'annuaire");
            status[0] = 503;
            assertThat(enrollment.submitIfNeeded()).isFalse();
            assertThat(progress.view().error()).isNotNull();
            assertThat(progress.view().submittedAt()).as("dernier envoi réussi gardé").isNotNull()
                    .isAfterOrEqualTo(first);
            assertThat(enrollment.submitIfNeeded()).as("avertissement une seule fois").isFalse();

            // Entrée publiée et lue : plus rien n'est envoyé.
            status[0] = 202;
            SignedDocument entry = new Organization("chu-exemple", ORG_KEYS).enroll(sent.request(), clock.instant(),
                    Duration.ofDays(90), 1);
            directory.synchronize(() -> List.of(entry));
            int before = received.size();
            assertThat(enrollment.submitIfNeeded()).isFalse();
            assertThat(received).hasSize(before);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void withoutCodeTheRequestIsSentPlainAndABadCodePreventsTheStart() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/enrollment-requests", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        DirectoryClient client = new DirectoryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(5));
        try {
            assertThat(new AutomaticEnrollment(directory, client, me, new DisabledTransport(), "Box", " ", clock)
                    .submitIfNeeded()).isTrue();
            assertThat(EnrollmentSubmission.fromJson(received.getFirst()).code()).isNull();
        } finally {
            server.stop(0);
        }
        assertThatThrownBy(() -> new DirectoryConfiguration().automaticEnrollment(directory, client, me,
                new DisabledTransport(), new com.imagunity.api.BoxProperties("Box"),
                new DirectoryProperties(true, null, null, null, null, Duration.ofMinutes(5), null, null, false, null,
                        "ABCD-1234"), clock, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("IMAGUNITY_DIRECTORY_ENROLLMENT_CODE");
        java.nio.file.Path keyFile = java.nio.file.Files.createTempDirectory("box").resolve("organisation.pub.json");
        com.imagunity.directory.KeyFiles.writeOrganizationPublic(keyFile, "chu-exemple", ORG_KEYS.publicKey());
        com.imagunity.api.EnrollmentProgress progress = new DirectoryConfiguration().enrollmentProgress(
                com.imagunity.directory.KeyFiles.readOrganizationPublic(keyFile),
                new DirectoryProperties(true, java.net.URI.create("https://annuaire.example"), null, null, null,
                        Duration.ofMinutes(5), null, null, false, null, " "));
        assertThat(progress.view().codeProvided()).as("code vide : aucun").isFalse();
        assertThat(progress.view().directoryUrl()).isEqualTo("https://annuaire.example");
        assertThat(progress.view().organizationShortCode()).isEqualTo(ORG_KEYS.publicKey().shortCode());
    }
}
