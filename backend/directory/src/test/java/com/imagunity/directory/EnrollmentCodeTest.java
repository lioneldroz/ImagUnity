package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Codes d'enrôlement, demandes transmises à l'annuaire et renouvellement des entrées (données synthétiques). */
class EnrollmentCodeTest {

    private static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    private static final HybridKeyPair BOX_KEYS = HybridKeyPair.generate();
    private static final Instant NOW = Instant.parse("2026-11-14T10:00:00Z");
    private static final UUID BOX = UUID.fromString("3f6c0d2e-8a8b-4b6e-9a51-0f6d3c2b1a00");

    private final Organization organization = new Organization("chu-exemple", ORG_KEYS);

    private static SignedDocument request(String name) {
        return EnrollmentRequest.create(BOX_KEYS, HybridKemKeyPair.generate().publicKey(), BOX, name,
                Map.of("iu", "iu://relais.exemple/q/abc"), NOW);
    }

    @Test
    void codesAreRandomReadableAndForgivingAtInput() {
        String code = EnrollmentCode.generate();
        assertThat(code).matches("[A-Z2-7]{4}(-[A-Z2-7]{4}){4}");
        assertThat(EnrollmentCode.generate()).isNotEqualTo(code);

        String typed = " " + code.toLowerCase().replace("-", " ") + " ";
        assertThat(EnrollmentCode.normalize(typed)).isEqualTo(code.replace("-", ""));
        assertThat(EnrollmentCode.id(typed)).isEqualTo(EnrollmentCode.id(code)).matches(EnrollmentCode.ID);

        assertThatThrownBy(() -> EnrollmentCode.normalize("ABCD-EFGH")).hasMessageContaining("20 caractères");
        assertThatThrownBy(() -> EnrollmentCode.normalize(null)).hasMessageContaining("invalide");
        assertThatThrownBy(() -> EnrollmentCode.normalize("ABCD-EFGH-IJKL-MNOP-QRS1"))
                .as("1 n'est pas dans l'alphabet").hasMessageContaining("invalide");
    }

    @Test
    void theProofTiesTheRequestToTheCode() {
        String code = EnrollmentCode.generate();
        SignedDocument request = request("CHU Exemple — imagerie Nord");
        String proof = EnrollmentCode.proof(code, request);

        assertThat(proof).matches(EnrollmentCode.PROOF);
        assertThat(EnrollmentCode.verify(code.toLowerCase(), request, proof)).isTrue();
        assertThat(EnrollmentCode.verify(EnrollmentCode.generate(), request, proof)).as("autre code").isFalse();
        assertThat(EnrollmentCode.verify(code, request("Autre nom"), proof)).as("autre demande").isFalse();
        assertThat(EnrollmentCode.verify(code, request, null)).isFalse();
    }

    @Test
    void submissionsCarryTheCodeOrNothing() {
        String code = EnrollmentCode.generate();
        SignedDocument request = request("CHU Exemple — imagerie Nord");

        EnrollmentSubmission proven = EnrollmentSubmission.of(request, code);
        EnrollmentSubmission back = EnrollmentSubmission.fromJson(proven.toJson());
        assertThat(back).isEqualTo(proven);
        assertThat(back.code()).isEqualTo(EnrollmentCode.id(code));
        assertThat(back.provenBy(code)).isTrue();
        assertThat(back.provenBy(EnrollmentCode.generate())).isFalse();

        EnrollmentSubmission plain = EnrollmentSubmission.of(request, " ");
        assertThat(plain.code()).isNull();
        assertThat(plain.provenBy(code)).isFalse();
        assertThat(EnrollmentSubmission.of(request, null).proof()).isNull();

        assertThatThrownBy(() -> new EnrollmentSubmission(request, EnrollmentCode.id(code), null))
                .hasMessageContaining("vont ensemble");
        assertThatThrownBy(() -> new EnrollmentSubmission(request, "pas-un-id", proven.proof()))
                .hasMessageContaining("mal formés");
        assertThatThrownBy(() -> new EnrollmentSubmission(request, EnrollmentCode.id(code), "court"))
                .hasMessageContaining("mal formés");
        assertThatThrownBy(() -> EnrollmentSubmission.fromJson("{\"code\":\"x\"}"))
                .isInstanceOf(DirectoryException.class).hasMessageContaining("illisible");
    }

    @Test
    void theResponsibleChoosesTheNameAndRenewsEntries() {
        SignedDocument entry = organization.enroll(request("nom proposé par la box"), NOW, Duration.ofDays(90), 1,
                List.of("imagerie-nord"), "CHU Exemple — imagerie Nord");
        BoxEntry enrolled = Json.read(entry.payload(), BoxEntry.class, "entrée");
        assertThat(enrolled.displayName()).isEqualTo("CHU Exemple — imagerie Nord");

        Instant later = NOW.plus(Duration.ofDays(80));
        SignedDocument renewed = organization.renew(entry, later, Duration.ofDays(90));
        BoxEntry r = Json.read(renewed.payload(), BoxEntry.class, "entrée");
        assertThat(renewed.isSignedBy(ORG_KEYS.publicKey())).isTrue();
        assertThat(r.serial()).isEqualTo(2);
        assertThat(r.validFrom()).isEqualTo(later);
        assertThat(r.validUntil()).isEqualTo(later.plus(Duration.ofDays(90)));
        assertThat(r.groups()).containsExactly("imagerie-nord");
        assertThat(r.transport()).isEqualTo(enrolled.transport());
        assertThat(r.signingKeys()).isEqualTo(enrolled.signingKeys());

        SignedDocument revoked = organization.revoke(renewed, later, "essai");
        assertThatThrownBy(() -> organization.renew(revoked, later, Duration.ofDays(90)))
                .hasMessageContaining("révoquée");
        Organization other = new Organization("chu-exemple", HybridKeyPair.generate());
        assertThatThrownBy(() -> other.renew(entry, later, Duration.ofDays(90)))
                .hasMessageContaining("non signée");
    }

    @Test
    void theBoxSendsItsRequestToTheDirectory() throws Exception {
        int[] status = {202};
        List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/enrollment-requests", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status[0], -1);
            exchange.close();
        });
        server.start();
        DirectoryClient client = new DirectoryClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(5));
        EnrollmentSubmission submission = EnrollmentSubmission.of(request("CHU Exemple — imagerie Nord"),
                EnrollmentCode.generate());
        try {
            assertThat(client.submitEnrollment(submission)).isTrue();
            assertThat(EnrollmentSubmission.fromJson(received.getFirst())).isEqualTo(submission);
            status[0] = 409;
            assertThat(client.submitEnrollment(submission)).as("déjà enrôlée").isFalse();
            status[0] = 422;
            assertThatThrownBy(() -> client.submitEnrollment(submission)).hasMessageContaining("422");
        } finally {
            server.stop(0);
        }
    }
}
