package com.imagunity.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import com.imagunity.domain.exam.StudyRestorer;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferState;
import com.imagunity.packaging.PackageReader;
import com.imagunity.packaging.PackagingConfig;
import com.imagunity.packaging.StudyPackager;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Box A et box B reliées par un faux transport : étude reçue en A, restituée en B, accusé revenu en A.
 * Chaque box a ses deux orchestrateurs, abonnés au même transport, comme dans l'application.
 */
class EndToEndAckTest {

    private static final String STUDY = "2.25.31";
    private static final PeerId A_SEEN_BY_B = new PeerId(9);
    private static final PeerId B_SEEN_BY_A = new PeerId(5);

    @TempDir
    Path dir;

    /** Horloge réelle : une relance doit dater son annonce après la première. */
    private final Clock clock = Clock.systemUTC();
    private final FakeTransport transportA = new FakeTransport();
    private final FakeTransport transportB = new FakeTransport();
    private final InMemoryRepositories.Transfers transfersA = new InMemoryRepositories.Transfers();
    private final List<StudyRestorer.Instance> pacsB = new ArrayList<>();
    private IOException pacsFailure;
    private int deliveredMessagesA;
    private int deliveredMessagesB;
    private SendOrchestrator sendOrchestratorA;
    private int offeredFiles;

    @BeforeEach
    void setUp() {
        InMemoryRepositories.Peers peersA = new InMemoryRepositories.Peers();
        peersA.save(new Peer("box-b", B_SEEN_BY_A.value(), "box-b", clock.instant()));
        InMemoryRepositories.Peers peersB = new InMemoryRepositories.Peers();
        peersB.save(new Peer("box-a", A_SEEN_BY_B.value(), "box-a", clock.instant()));
        StudyRestorer restorer = (study, instances) -> {
            if (pacsFailure != null) {
                throw pacsFailure;
            }
            pacsB.addAll(instances);
        };

        sendOrchestratorA = new SendOrchestrator(transfersA, peersA, new StudyPackager(new PackagingConfig(2500, 3)),
                transportA, dir.resolve("a/outbox"), Runnable::run, clock);
        sendOrchestratorA.start();
        new ReceiveOrchestrator(new InMemoryRepositories.Inbound(), peersA, transportA, new PackageReader(1L << 30),
                restorer, dir.resolve("a/inbox"), 1L << 30, Runnable::run, clock).start();
        new SendOrchestrator(new InMemoryRepositories.Transfers(), peersB, new StudyPackager(PackagingConfig.defaults()),
                transportB, dir.resolve("b/outbox"), Runnable::run, clock).start();
        new ReceiveOrchestrator(new InMemoryRepositories.Inbound(), peersB, transportB, new PackageReader(1L << 30),
                restorer, dir.resolve("b/inbox"), 1L << 30, Runnable::run, clock).start();
    }

    private ReceivedStudy study() throws IOException {
        List<ReceivedInstance> instances = new ArrayList<>();
        for (int s = 1; s <= 2; s++) {
            Path seriesDir = Files.createDirectories(dir.resolve("a/incoming/" + s));
            for (int i = 1; i <= 2; i++) {
                String sop = STUDY + "." + s + "." + i;
                Path file = Files.write(seriesDir.resolve(sop + ".dcm"), (sop + " ").repeat(200).getBytes());
                instances.add(new ReceivedInstance("1.2", sop, STUDY + "." + s, "1.2", file, Files.size(file)));
            }
        }
        return new ReceivedStudy(STUDY, "IU_TO_B", "box-b", Set.of("ORTHANC_A"), instances, clock.instant(),
                clock.instant());
    }

    /** Fait circuler fichiers et messages entre les deux box jusqu'à ce que plus rien ne bouge. */
    private void relay() throws IOException {
        boolean moved = true;
        while (moved) {
            moved = false;
            for (; offeredFiles < transportA.files.size(); offeredFiles++) {
                FakeTransport.SentFile f = transportA.files.get(offeredFiles);
                transportA.emit(new TransportEvent.FileSent(f.peer(), f.fileId()));
                transportB.emit(new TransportEvent.FileOffered(A_SEEN_BY_B, f.fileId(),
                        f.file().getFileName().toString(), Files.size(f.file())));
                moved = true;
            }
            for (Map.Entry<Long, Path> d : List.copyOf(transportB.downloads.entrySet())) {
                transportB.downloads.remove(d.getKey());
                Path source = transportA.files.stream().filter(f -> f.fileId() == d.getKey()).findFirst()
                        .orElseThrow().file();
                Files.copy(source, d.getValue());
                transportB.emit(new TransportEvent.FileReceived(A_SEEN_BY_B, d.getKey(), d.getValue()));
                moved = true;
            }
            for (; deliveredMessagesB < transportA.messages.size(); deliveredMessagesB++) {
                transportB.emit(new TransportEvent.MessageReceived(A_SEEN_BY_B,
                        transportA.messages.get(deliveredMessagesB).text()));
                moved = true;
            }
            for (; deliveredMessagesA < transportB.messages.size(); deliveredMessagesA++) {
                transportA.emit(new TransportEvent.MessageReceived(B_SEEN_BY_A,
                        transportB.messages.get(deliveredMessagesA).text()));
                moved = true;
            }
        }
    }

    @Test
    void studyRestoredInBIsAcknowledgedInA() throws IOException {
        submit(study());
        relay();

        Transfer transfer = transfersA.only();
        assertThat(transfer.state()).isEqualTo(TransferState.ACKNOWLEDGED);
        assertThat(transfer.partCount()).as("plusieurs paquets").isGreaterThan(1).isEqualTo(transportA.files.size());
        assertThat(pacsB).hasSize(4);
        assertThat(dir.resolve("a/outbox").resolve(transfer.id().toString())).doesNotExist();
        assertThat(transportB.messages).singleElement()
                .satisfies(m -> assertThat(m.text()).contains(TransferMessages.ACK).contains("RESTORED"));
    }

    @Test
    void failureInBIsReportedToA() throws IOException {
        pacsFailure = new IOException("PACS B injoignable");

        submit(study());
        relay();

        Transfer transfer = transfersA.only();
        assertThat(transfer.state()).isEqualTo(TransferState.FAILED);
        assertThat(transfer.errorMessage())
                .isEqualTo("Échec chez le destinataire : Restitution impossible : PACS B injoignable");
        assertThat(dir.resolve("a/outbox").resolve(transfer.id().toString())).isNotEmptyDirectory();
    }

    private void submit(ReceivedStudy study) {
        sendOrchestratorA.submit(study);
    }

    @Test
    void transferFailedInBCanBeRetriedFromA() throws IOException {
        pacsFailure = new IOException("PACS B injoignable");
        submit(study());
        relay();
        Transfer failed = transfersA.only();
        assertThat(failed.state()).isEqualTo(TransferState.FAILED);

        pacsFailure = null;
        sendOrchestratorA.retry(failed.id()).join();
        relay();

        assertThat(transfersA.only().state()).isEqualTo(TransferState.ACKNOWLEDGED);
        assertThat(pacsB).hasSize(4);
        assertThat(dir.resolve("a/outbox").resolve(failed.id().toString())).doesNotExist();
    }
}
