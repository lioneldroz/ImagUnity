package com.imagunity.directory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Étape 5 de la fédération : versions du protocole annoncées, négociées, reportées au renouvellement. */
class ProtocolsTest {

    private static final Instant NOW = Instant.parse("2026-12-01T10:00:00Z");
    private static final Organization A = new Organization("grand-est", HybridKeyPair.generate());
    private static final Organization B = new Organization("bourgogne-fc", HybridKeyPair.generate());

    @Test
    void versionsAreCheckedOrderedAndNegotiated() {
        assertThat(Protocols.of(List.of("iu1.10", "iu2.0", "iu1.9"))).containsExactly("iu1.9", "iu1.10", "iu2.0");
        assertThat(Protocols.of(null)).isEmpty();
        assertThat(Protocols.of(List.of())).isEmpty();
        for (String bad : List.of("iu1", "IU1.0", "iu01.0", "iu1.01", "iu0.1", "v1.0", "iu1.0-beta")) {
            assertThatThrownBy(() -> Protocols.of(List.of(bad))).as(bad).hasMessageContaining("invalide");
        }
        assertThatThrownBy(() -> Protocols.of(Arrays.asList("iu1.0", null))).hasMessageContaining("absente");
        assertThatThrownBy(() -> Protocols.of(List.of("iu1.0", "iu1.0"))).hasMessageContaining("double");
        assertThatThrownBy(() -> Protocols.of(List.of("iu1.0", "iu1.1", "iu1.2", "iu1.3", "iu1.4", "iu1.5", "iu1.6",
                "iu1.7", "iu1.8"))).hasMessageContaining("Trop");

        assertThat(Protocols.announced(null)).containsExactly(Protocols.INITIAL);
        assertThat(Protocols.announced(List.of())).containsExactly(Protocols.INITIAL);
        assertThat(Protocols.SUPPORTED).contains(Protocols.INITIAL);
        assertThat(Protocols.negotiate(List.of("iu1.0", "iu1.1", "iu2.0"), List.of("iu1.0", "iu1.1")))
                .hasValue("iu1.1");
        assertThat(Protocols.negotiate(List.of(), List.of("iu1.0", "iu1.1"))).hasValue("iu1.0");
        assertThat(Protocols.negotiate(List.of("iu2.0"), List.of())).isEmpty();
        assertThat(Protocols.refusal(List.of("iu1.0"), List.of("iu1.0"), "Box B")).isEmpty();
        assertThat(Protocols.refusal(List.of("iu1.0"), List.of("iu2.0", "iu2.1"), "Box B")).hasValueSatisfying(m ->
                assertThat(m).isEqualTo("Aucune version du protocole en commun avec « Box B » : cette box parle "
                        + "iu1.0, l'autre iu2.0, iu2.1 ; mettre à jour la plus ancienne"));
    }

    @Test
    void entriesCarryTheVersionsOfTheRequestAndKeepThemUntilAReportChangesThem() {
        HybridKeyPair keys = HybridKeyPair.generate();
        UUID box = UUID.randomUUID();
        SignedDocument request = EnrollmentRequest.create(keys, null, box, "Box", Map.of(), NOW);
        assertThat(request.payload()).contains("\"protocols\":[\"iu1.0\"]");
        assertThat(EnrollmentRequest.verify(request).protocols()).isEqualTo(Protocols.SUPPORTED);
        SignedDocument entry = A.enroll(request, NOW, Duration.ofDays(90), 1);
        BoxEntry e = Json.read(entry.payload(), BoxEntry.class, "e");
        assertThat(e.protocols()).isEqualTo(Protocols.SUPPORTED);

        // Entrée antérieure aux versions : rien dans le document signé, iu1.0 annoncée.
        SignedDocument old = A.enroll(SignedDocument.sign(Json.write(new EnrollmentRequest("IU1", box, "Box",
                keys.publicKey().encoded(), Map.of(), NOW)), keys), NOW, Duration.ofDays(90), 1);
        assertThat(old.payload()).doesNotContain("protocols");
        BoxEntry o = Json.read(old.payload(), BoxEntry.class, "o");
        assertThat(o.protocols()).isEmpty();
        assertThat(o.announcedProtocols()).containsExactly(Protocols.INITIAL);
        assertThat(Json.read(A.renew(old, NOW, Duration.ofDays(90)).payload(), BoxEntry.class, "r").protocols())
                .isEmpty();

        SignedDocument renewed = A.renew(entry, NOW, Duration.ofDays(90), List.of("iu1.1", "iu1.0"));
        assertThat(Json.read(renewed.payload(), BoxEntry.class, "r").protocols()).containsExactly("iu1.0", "iu1.1");
        assertThat(Json.read(A.renew(renewed, NOW, Duration.ofDays(90), null).payload(), BoxEntry.class, "r")
                .protocols()).as("sans compte rendu : gardées").containsExactly("iu1.0", "iu1.1");
        assertThat(Json.read(A.revoke(renewed, NOW, "essai").payload(), BoxEntry.class, "v").protocols())
                .containsExactly("iu1.0", "iu1.1");

        // Compte rendu : versions du logiciel de la box ; un compte rendu ancien (sans le champ) se lit encore.
        ConfigurationReport report = ConfigurationReport.of(box, 0, null, 0, null, NOW);
        assertThat(report.protocols()).isEqualTo(Protocols.SUPPORTED);
        assertThat(new ConfigurationReport(ConfigurationReport.TYPE, box, 0, null, 0, null, NOW).protocols()).isEmpty();
        SignedDocument future = SignedDocument.sign("{\"type\":\"" + ConfigurationReport.TYPE + "\",\"boxId\":\"" + box
                + "\",\"applied\":0,\"refused\":0,\"reportedAt\":\"" + NOW + "\",\"protocols\":[\"iu1.0\",\"iu2.0\"],"
                + "\"champFutur\":1}", keys);
        assertThat(ConfigurationReport.verify(future, id -> java.util.Optional.of(keys.publicKey())).protocols())
                .containsExactly("iu1.0", "iu2.0");
    }

    @Test
    void aBoxRefusesToExchangeWithABoxItCannotSpeakTo() {
        SerialMemory serials = SerialMemory.inMemory();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        LocalDirectory directory = new LocalDirectory(new EntryVerifier(A.id(), A.publicKey(), serials, clock),
                new LocalDirectoryTest.MemoryCache(), clock)
                .federate(new RecognitionVerifier(A.id(), A.publicKey(), serials), serials);
        UUID self = UUID.randomUUID();
        UUID future = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        directory.synchronize(() -> List.of(entry(A, self, List.of("iu1.0")), entry(A, future, List.of("iu2.0")),
                entry(A, old, List.of())));
        assertThat(directory.protocol(old)).hasValue("iu1.0");
        assertThat(directory.protocolRefusal(old)).isEmpty();
        assertThat(directory.boxRefusal(self, old)).isEmpty();
        assertThat(directory.protocol(future)).isEmpty();
        assertThat(directory.protocolRefusal(future)).hasValueSatisfying(m -> assertThat(m)
                .contains("« Box iu2.0 »").contains("l'autre iu2.0"));
        assertThat(directory.boxRefusal(self, future)).isEqualTo(directory.protocolRefusal(future));
        assertThat(directory.exchangeRefusal(self, future)).as("le relais n'en tient pas compte").isEmpty();
        assertThat(directory.protocolRefusal(UUID.randomUUID())).as("box inconnue : refusée à part").isEmpty();
        assertThat(directory.protocol(UUID.randomUUID())).isEmpty();

        // Box d'un partenaire : même règle ; l'accord passe d'abord.
        String fpB = B.publicKey().fingerprint();
        UUID nord = UUID.randomUUID();
        directory.synchronizeRecognitions(() -> List.of(A.recognize(new Recognition.Partner(B.id(),
                B.publicKey().encoded(), "https://annuaire.b.example", List.of("https://relais.b.example")),
                new Recognition.Scope(List.of("*"), List.of("*")), NOW, Duration.ofDays(90))));
        directory.synchronizePartner(fpB, () -> List.of(entry(B, nord, List.of("iu2.0"))));
        assertThat(directory.protocolRefusal(nord)).isPresent();
        assertThat(directory.boxRefusal(self, nord)).hasValueSatisfying(m -> assertThat(m)
                .contains("Aucune version du protocole"));
    }

    @Test
    void theResponsibleToolReadsTheVersionsOfTheLastSignedReport() throws Exception {
        HybridKeyPair keys = HybridKeyPair.generate();
        UUID box = UUID.randomUUID();
        BoxEntry entry = Json.read(A.enroll(EnrollmentRequest.create(keys, null, box, "Box", Map.of(), NOW), NOW,
                Duration.ofDays(90), 1).payload(), BoxEntry.class, "e");
        String[] body = {null};
        int[] status = {404};
        String[] authorization = {null};
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/directory/v1/configurations/reports/", exchange -> {
            authorization[0] = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] bytes = body[0] == null ? new byte[0] : body[0].getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status[0], status[0] == 200 ? bytes.length : -1);
            if (status[0] == 200) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try {
            DirectoryCalls calls = new DirectoryCalls("http://127.0.0.1:" + server.getAddress().getPort() + "/",
                    () -> "responsable:secret");
            assertThat(calls.reportedProtocols(entry)).as("pas de compte rendu").isNull();
            assertThat(authorization[0]).startsWith("Basic ");
            status[0] = 200;
            body[0] = new ConfigurationReport(ConfigurationReport.TYPE, box, 0, null, 0, null, NOW,
                    List.of("iu1.0", "iu1.1")).sign(keys).toJson();
            assertThat(calls.reportedProtocols(entry)).containsExactly("iu1.0", "iu1.1");
            body[0] = new ConfigurationReport(ConfigurationReport.TYPE, box, 0, null, 0, null, NOW).sign(keys).toJson();
            assertThat(calls.reportedProtocols(entry)).as("compte rendu sans versions").isNull();
            body[0] = ConfigurationReport.of(box, 0, null, 0, null, NOW).sign(HybridKeyPair.generate()).toJson();
            assertThat(calls.reportedProtocols(entry)).as("signé par une autre clé").isNull();
            body[0] = ConfigurationReport.of(UUID.randomUUID(), 0, null, 0, null, NOW).sign(keys).toJson();
            assertThat(calls.reportedProtocols(entry)).as("d'une autre box").isNull();
            body[0] = "illisible";
            assertThat(calls.reportedProtocols(entry)).isNull();
        } finally {
            server.stop(0);
        }
    }

    private static SignedDocument entry(Organization o, UUID box, List<String> protocols) {
        String name = "Box " + (protocols.isEmpty() ? "ancienne" : protocols.getFirst());
        return o.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), HybridKemKeyPair.generate().publicKey(), box,
                name, Map.of("iu", (o == B ? "https://relais.b.example" : "https://relais.example") + "/iu/v1/queues/q"),
                NOW, protocols), NOW, Duration.ofDays(90),
                1, List.of(), name);
    }
}
