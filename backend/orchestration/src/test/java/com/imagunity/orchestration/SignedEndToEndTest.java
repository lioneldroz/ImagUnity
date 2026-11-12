package com.imagunity.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import com.imagunity.domain.exam.StudyRestorer;
import com.imagunity.domain.peer.Peer;
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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transfert entre deux box enrôlées, annonces et accusés signés et vérifiés avec l'annuaire. */
class SignedEndToEndTest {

    private static final PeerId A_SEEN_BY_B = new PeerId(9);
    private static final PeerId B_SEEN_BY_A = new PeerId(5);

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(DirectoryFixture.NOW.plusSeconds(60), ZoneOffset.UTC);
    private final DirectoryFixture fixture = new DirectoryFixture();
    private final BoxIdentity boxA = fixture.box();
    private final BoxIdentity boxB = fixture.box();
    private final FakeTransport transportA = new FakeTransport();
    private final FakeTransport transportB = new FakeTransport();
    private final InMemoryRepositories.Transfers transfersA = new InMemoryRepositories.Transfers();
    private final InMemoryRepositories.Inbound inboundB = new InMemoryRepositories.Inbound();
    private final List<StudyRestorer.Instance> pacsB = new ArrayList<>();
    private SendOrchestrator sender;
    private int deliveredMessagesB;
    private int deliveredMessagesA;

    private void start() {
        fixture.enroll(boxA);
        fixture.enroll(boxB);
        LocalDirectory directory = fixture.directory(clock);
        InMemoryRepositories.Peers peersA = new InMemoryRepositories.Peers();
        peersA.save(new Peer("box-b", B_SEEN_BY_A.value(), "box-b", DirectoryFixture.NOW).boundTo(boxB.boxId()));
        InMemoryRepositories.Peers peersB = new InMemoryRepositories.Peers();
        peersB.save(new Peer("box-a", A_SEEN_BY_B.value(), "box-a", DirectoryFixture.NOW).boundTo(boxA.boxId()));
        sender = new SendOrchestrator(transfersA, peersA, new StudyPackager(PackagingConfig.defaults()), transportA,
                dir.resolve("a/outbox"), Runnable::run, clock, new DirectoryPeerAuthorization(directory),
                new MessageSigner(boxA, directory));
        sender.start();
        new ReceiveOrchestrator(inboundB, peersB, transportB, new PackageReader(1L << 30),
                (study, instances) -> pacsB.addAll(instances), dir.resolve("b/inbox"), 1L << 30, Runnable::run, clock,
                new DirectoryPeerAuthorization(directory), new MessageSigner(boxB, directory)).start();
    }

    private ReceivedStudy study() throws IOException {
        Path series = Files.createDirectories(dir.resolve("a/incoming/1"));
        Path file = Files.write(series.resolve("i.dcm"), "2.25.40.1.1".getBytes());
        return new ReceivedStudy("2.25.40", "IU_TO_B", "box-b", Set.of(), List.of(new ReceivedInstance("1.2",
                "2.25.40.1.1", "2.25.40.1", "1.2", file, Files.size(file))), clock.instant(), clock.instant());
    }

    private void relay() throws IOException {
        int offered = 0;
        boolean moved = true;
        while (moved) {
            moved = false;
            for (; offered < transportA.files.size(); offered++) {
                FakeTransport.SentFile f = transportA.files.get(offered);
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
    void signedAnnouncementAndAckCompleteTheTransfer() throws IOException {
        start();

        sender.handleStudy(study());
        relay();

        assertThat(transfersA.only().state()).isEqualTo(TransferState.ACKNOWLEDGED);
        assertThat(pacsB).hasSize(1);
        assertThat(transportA.messages).singleElement().satisfies(m -> assertThat(m.text()).contains("\"version\":2"));
        assertThat(transportB.messages).singleElement().satisfies(m -> assertThat(m.text()).contains("\"version\":2"));
        assertThat(inboundB.transfers.values()).singleElement()
                .satisfies(t -> assertThat(t.announcement()).contains("\"version\":1"));
    }

    @Test
    void unsignedAnnouncementFromABoundPartnerIsIgnored() throws IOException {
        start();
        TransferAnnouncement forged = new TransferAnnouncement(java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID().toString(), "2.25.41", List.of(new TransferAnnouncement.Part(1, 1,
                        "x", 1, "a".repeat(64))), clock.instant());

        transportB.emit(new TransportEvent.MessageReceived(A_SEEN_BY_B, new TransferMessages().encode(forged)));
        transportB.emit(new TransportEvent.MessageReceived(new PeerId(66), new TransferMessages().encode(forged)));

        assertThat(inboundB.transfers).isEmpty();
    }
}
