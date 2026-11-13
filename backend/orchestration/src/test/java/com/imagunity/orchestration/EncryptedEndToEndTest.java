package com.imagunity.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.SealedFiles;
import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import com.imagunity.domain.exam.StudyRestorer;
import com.imagunity.domain.inbound.InboundTransfer;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.transfer.TransferPart;
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
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Transfert entre deux box enrôlées, paquets chiffrés pour la box destinataire avec les clés de l'annuaire. */
class EncryptedEndToEndTest {

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
    private LocalDirectory directory;
    private SendOrchestrator sender;
    private int deliveredMessagesB;
    private int deliveredMessagesA;

    /**
     * @param keysOfB     l'entrée de B publie ses clés de chiffrement
     * @param encryptionA A chiffre ses paquets (sinon, il les envoie en clair, comme une box d'avant le chiffrement)
     * @param keysUsedByB clés avec lesquelles B déchiffre
     */
    private void start(boolean keysOfB, boolean encryptionA, BoxIdentity keysUsedByB) {
        fixture.enroll(boxA);
        fixture.enroll(boxB, DirectoryFixture.NOW, Duration.ofDays(30), 1, keysOfB);
        directory = fixture.directory(clock);
        InMemoryRepositories.Peers peersA = new InMemoryRepositories.Peers();
        peersA.save(new Peer("box-b", B_SEEN_BY_A.value(), "box-b", DirectoryFixture.NOW).boundTo(boxB.boxId()));
        InMemoryRepositories.Peers peersB = new InMemoryRepositories.Peers();
        peersB.save(new Peer("box-a", A_SEEN_BY_B.value(), "box-a", DirectoryFixture.NOW).boundTo(boxA.boxId()));
        sender = new SendOrchestrator(transfersA, peersA, new StudyPackager(PackagingConfig.defaults()), transportA,
                dir.resolve("a/outbox"), Runnable::run, clock, new DirectoryPeerAuthorization(directory),
                new MessageSigner(boxA, directory),
                encryptionA ? new DirectoryPacketEncryption(directory, boxA) : PacketEncryption.none());
        sender.start();
        new ReceiveOrchestrator(inboundB, peersB, transportB, new PackageReader(1L << 30),
                (study, instances) -> pacsB.addAll(instances), dir.resolve("b/inbox"), 1L << 30, Runnable::run, clock,
                new DirectoryPeerAuthorization(directory), new MessageSigner(boxB, directory),
                new DirectoryPacketEncryption(directory, keysUsedByB)).start();
    }

    private void start() {
        start(true, true, boxB);
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
    void packetsTravelEncryptedForTheRecipientBox() throws IOException {
        start();

        sender.handleStudy(study());
        Path sent = transportA.files.getFirst().file();
        assertThat(sent.getFileName().toString()).endsWith(".tar.zst.iu1");
        assertThat(SealedFiles.isSealed(sent)).isTrue();
        try (var outbox = Files.list(sent.getParent())) {
            assertThat(outbox).as("paquet en clair supprimé").containsExactly(sent);
        }
        TransferPart part = transfersA.parts(transfersA.only().id()).getFirst();
        assertThat(part.file()).isEqualTo(sent);
        assertThat(part.size()).isEqualTo(Files.size(sent));
        assertThat(part.sha256()).isEqualTo(ReceiveOrchestrator.sha256(sent));
        relay();

        assertThat(transfersA.only().state()).isEqualTo(TransferState.ACKNOWLEDGED);
        assertThat(pacsB).singleElement().satisfies(i -> assertThat(i.sopInstanceUid()).isEqualTo("2.25.40.1.1"));
        try (var inbox = Files.walk(dir.resolve("b/inbox"))) {
            assertThat(inbox.filter(Files::isRegularFile)).as("rien ne reste en clair chez B").isEmpty();
        }
    }

    @Test
    void recipientWithoutEncryptionKeysIsRefusedUntilItsEntryIsReissued() throws IOException {
        start(false, true, boxB);

        sender.handleStudy(study());

        assertThat(transfersA.only().state()).isEqualTo(TransferState.FAILED);
        assertThat(transfersA.only().errorMessage()).contains("sans clés de chiffrement").contains("réémettre");
        assertThat(transportA.files).isEmpty();

        fixture.enroll(boxB, DirectoryFixture.NOW, Duration.ofDays(30), 2, true);
        directory.synchronize(() -> List.copyOf(fixture.published));
        sender.doRetry(transfersA.only().id());
        relay();

        assertThat(transfersA.only().state()).isEqualTo(TransferState.ACKNOWLEDGED);
        assertThat(transportA.files.getFirst().file().getFileName().toString()).endsWith(".iu1");
        assertThat(pacsB).hasSize(1);
    }

    @Test
    void plainPacketsAreRefusedByTheRecipient() throws IOException {
        start(true, false, boxB);

        sender.handleStudy(study());
        assertThat(transportA.files.getFirst().file().getFileName().toString()).endsWith(".tar.zst");
        relay();

        assertThat(pacsB).isEmpty();
        assertThat(inboundB.transfers.values()).singleElement()
                .satisfies(t -> assertThat(t.errorMessage()).contains("sans chiffrement applicatif"));
        assertThat(transfersA.only().state()).isEqualTo(TransferState.FAILED);
    }

    @Test
    void aPacketEncryptedForAnotherBoxIsNotRestored() throws IOException {
        start(true, true, fixture.box());

        sender.handleStudy(study());
        relay();

        assertThat(pacsB).isEmpty();
        assertThat(inboundB.transfers.values()).singleElement().satisfies(t -> {
            assertThat(t.state()).isEqualTo(InboundTransfer.State.FAILED);
            assertThat(t.errorMessage()).contains("autre box");
        });
        assertThat(transfersA.only().state()).isEqualTo(TransferState.FAILED);
    }

    @Test
    void sealingFailureFailsTheTransfer() throws IOException {
        start();
        sender.handleStudy(study());
        TransferPart part = transfersA.parts(transfersA.only().id()).getFirst();
        assertThat(part.file().getFileName().toString()).endsWith(".iu1");

        // Paquet en clair disparu avant son chiffrement (relance d'un paquet resté en clair).
        Path plain = part.file().resolveSibling("absent.tar.zst");
        transfersA.updatePart(part.sealedAs(plain, 1, "a".repeat(64)));
        transfersA.update(transfersA.only().failed("essai", clock.instant()));
        assertThatThrownBy(() -> sender.doRetry(transfersA.only().id())).hasMessageContaining("absent");
        Files.writeString(plain, "pas un paquet");
        sender.doRetry(transfersA.only().id());
        assertThat(transfersA.only().state()).isEqualTo(TransferState.ANNOUNCED);
        assertThat(transportA.files).hasSize(2);
    }

    @Test
    void withoutDirectoryAnEncryptedPacketCannotBeOpened() throws IOException {
        Path packet = Files.writeString(dir.resolve("p.tar.zst"), "x");
        PacketEncryption none = PacketEncryption.none();

        assertThat(none.seal(null, packet)).isEqualTo(packet);
        assertThat(none.open(packet, "p.tar.zst", dir)).isEqualTo(packet);
        assertThatThrownBy(() -> none.open(packet, "p.tar.zst.iu1", dir)).hasMessageContaining("annuaire");
    }
}
