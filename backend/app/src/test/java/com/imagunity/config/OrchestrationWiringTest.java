package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.dicom.StudyListener;
import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferRepository;
import com.imagunity.domain.transfer.TransferState;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Câblage complet dans l'application : entrée DICOM → orchestrateur → base → transport, et API d'appairage.
 * Le transport est désactivé (pas de relais IU-T en test) : le transfert va jusqu'à l'envoi, qui échoue
 * avec un message explicite.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrchestrationWiringTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("imagunity.dicom.enabled", () -> "true");
        registry.add("imagunity.dicom.bind-address", () -> "127.0.0.1");
        // Un seul port pour tout le contexte : le fournisseur peut être réévalué à chaque lecture de la propriété.
        int port = freePort();
        registry.add("imagunity.dicom.port", () -> port);
        registry.add("imagunity.dicom.storage-dir", () -> dir.resolve("incoming").toString());
        registry.add("imagunity.dicom.allowed-calling-aets[0]", () -> "ORTHANC_A");
        registry.add("imagunity.dicom.routes[0].called-aet", () -> "IU_TO_B");
        registry.add("imagunity.dicom.routes[0].destination", () -> "box-b");
        registry.add("imagunity.orchestration.outbox-dir", () -> dir.resolve("outbox").toString());
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:wiring-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
    }

    @Autowired
    private StudyListener studyListener;

    @Autowired
    private TransferRepository transfers;

    @Autowired
    private PeerRepository peers;

    @Autowired
    private Environment environment;

    @Autowired
    private List<SmartLifecycle> lifecycles;

    @Test
    void receivedStudyIsPackagedAndHandedToTheTransport() throws Exception {
        peers.save(new Peer("box-b", 5, "box-b", Instant.now()));
        Path file = Files.write(Files.createDirectories(dir.resolve("incoming/IU_TO_B/2.25.4/2.25.4.1"))
                .resolve("2.25.4.1.1.dcm"), new byte[500]);
        ReceivedStudy study = new ReceivedStudy("2.25.4", "IU_TO_B", "box-b", Set.of("ORTHANC_A"),
                List.of(new ReceivedInstance("1.2", "2.25.4.1.1", "2.25.4.1", "1.2", file, 500)), Instant.now(),
                Instant.now());

        studyListener.studyReceived(study);

        Transfer transfer = awaitTerminal("2.25.4");
        assertThat(transfer.state()).isEqualTo(TransferState.FAILED);
        assertThat(transfer.errorMessage()).contains("désactivé");
        assertThat(transfer.partCount()).isEqualTo(1);
        assertThat(transfers.parts(transfer.id())).singleElement()
                .satisfies(p -> assertThat(p.file()).exists().startsWith(dir.resolve("outbox")));
        assertThat(file).as("instance transférée dans le paquet").doesNotExist();
    }

    @Test
    void peerApiAnswersOverHttp() throws Exception {
        String base = "http://127.0.0.1:" + environment.getProperty("local.server.port");
        HttpClient http = HttpClient.newHttpClient();

        HttpResponse<String> list = http.send(authorized(URI.create(base + "/api/v1/peers")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> invite = http.send(authorized(URI.create(base + "/api/v1/peers/invitations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"name\":\"box-c\"}")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(invite.statusCode()).as("plus d'invitation : appairage par l'annuaire").isGreaterThanOrEqualTo(400);
    }

    @Test
    void consoleApiFollowsTransfersOverHttp() throws Exception {
        String base = "http://127.0.0.1:" + environment.getProperty("local.server.port");
        HttpClient http = HttpClient.newHttpClient();
        // Flux SSE ouvert avant le transfert : il doit en recevoir les changements d'état.
        HttpResponse<java.util.stream.Stream<String>> stream = http.send(
                authorized(URI.create(base + "/api/v1/events")).build(),
                HttpResponse.BodyHandlers.ofLines());
        java.util.concurrent.BlockingQueue<String> lines = new java.util.concurrent.LinkedBlockingQueue<>();
        Thread.ofVirtual().start(() -> stream.body().forEach(lines::add));

        peers.save(new Peer("box-b", 5, "box-b", Instant.now()));
        Path file = Files.write(Files.createDirectories(dir.resolve("incoming/IU_TO_B/2.25.5/2.25.5.1"))
                .resolve("2.25.5.1.1.dcm"), new byte[500]);
        studyListener.studyReceived(new ReceivedStudy("2.25.5", "IU_TO_B", "box-b", Set.of("ORTHANC_A"),
                List.of(new ReceivedInstance("1.2", "2.25.5.1.1", "2.25.5.1", "1.2", file, 500)), Instant.now(),
                Instant.now()));
        Transfer transfer = awaitTerminal("2.25.5");

        HttpResponse<String> list = http.send(authorized(URI.create(base + "/api/v1/transfers")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> detail = http.send(authorized(
                URI.create(base + "/api/v1/transfers/" + transfer.id())).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> overview = http.send(authorized(URI.create(base + "/api/v1/overview")).build(),
                HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> retry = http.send(authorized(
                        URI.create(base + "/api/v1/transfers/" + transfer.id() + "/retry"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> unknown = http.send(authorized(
                URI.create(base + "/api/v1/transfers/" + UUID.randomUUID())).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> inbound = http.send(authorized(
                URI.create(base + "/api/v1/inbound-transfers?limit=10")).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains(transfer.id().toString()).contains("\"studyInstanceUid\":\"2.25.5\"");
        assertThat(detail.body()).contains("\"parts\"").contains(".tar.zst").doesNotContain(dir.toString());
        assertThat(overview.body()).contains("\"outbound\"").contains("\"FAILED\"");
        assertThat(retry.statusCode()).as("relance acceptée, nouvel échec : transport désactivé").isEqualTo(200);
        assertThat(retry.body()).contains("\"state\":\"FAILED\"");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(inbound.statusCode()).isEqualTo(200);
        assertThat(inbound.body()).isEqualTo("[]");

        java.util.List<String> received = new java.util.ArrayList<>();
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && received.stream().noneMatch(l -> l.contains("\"FAILED\""))) {
            String line = lines.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (line != null) {
                received.add(line);
            }
        }
        assertThat(received).contains("event:ready", "event:transfer");
        assertThat(received).anySatisfy(l -> assertThat(l).startsWith("data:").contains("\"PACKAGED\""));
        assertThat(received).anySatisfy(l -> assertThat(l).startsWith("data:").contains("\"FAILED\""));
        stream.body().close();
    }

    @Test
    void orchestratorStartsBeforeDicomInput() {
        SmartLifecycle orchestration = lifecycles.stream().filter(l -> l.getPhase() == OrchestrationConfiguration.PHASE)
                .findFirst().orElseThrow();

        assertThat(orchestration.isRunning()).isTrue();
        assertThat(lifecycles).anySatisfy(l -> assertThat(l.getPhase()).isGreaterThan(OrchestrationConfiguration.PHASE));
        orchestration.stop();
        assertThat(orchestration.isRunning()).isFalse();
        orchestration.start();
    }

    /** Jusqu'à 60 s : le premier chargement de la bibliothèque native zstd peut prendre une dizaine de secondes. */
    private Transfer awaitTerminal(String study) throws InterruptedException {
        for (int i = 0; i < 1200; i++) {
            Optional<Transfer> found = transfers.findByStates(Set.of(TransferState.FAILED, TransferState.ANNOUNCED))
                    .stream().filter(t -> t.studyInstanceUid().equals(study)).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Transfert non terminé");
    }

    /** Requête avec le compte opérateur de test et l'en-tête de la console. */
    private static HttpRequest.Builder authorized(URI uri) {
        String credentials = java.util.Base64.getEncoder()
                .encodeToString("operateur:mot-de-passe-de-test".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(uri).header("Authorization", "Basic " + credentials)
                .header(SecurityConfiguration.CONSOLE_HEADER, "1");
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
