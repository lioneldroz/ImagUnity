package com.imagunity.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferPart;
import com.imagunity.domain.transfer.TransferState;
import com.imagunity.packaging.PackagingConfig;
import com.imagunity.packaging.StudyPackager;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SendOrchestratorTest {

    private static final String STUDY = "2.25.9";

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryRepositories.Transfers transfers = new InMemoryRepositories.Transfers();
    private final InMemoryRepositories.Peers peers = new InMemoryRepositories.Peers();
    private final FakeTransport transport = new FakeTransport();
    private final TransferMessages messages = new TransferMessages();
    private SendOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        peers.save(new Peer("box-b", 5, "box-b", clock.instant()));
        orchestrator = orchestrator(PackagingConfig.defaults());
    }

    private SendOrchestrator orchestrator(PackagingConfig config) {
        return new SendOrchestrator(transfers, peers, new StudyPackager(config), transport, dir.resolve("outbox"),
                Runnable::run, clock);
    }

    private ReceivedStudy study(String destination, int series, int perSeries) throws IOException {
        List<ReceivedInstance> instances = new ArrayList<>();
        for (int s = 1; s <= series; s++) {
            Path seriesDir = Files.createDirectories(dir.resolve("incoming/IU_TO_B/" + STUDY + "/" + STUDY + "." + s));
            for (int i = 1; i <= perSeries; i++) {
                String sop = STUDY + "." + s + "." + i;
                Path file = Files.write(seriesDir.resolve(sop + ".dcm"), new byte[1000 + i]);
                instances.add(new ReceivedInstance("1.2.840.10008.5.1.4.1.1.2", sop, STUDY + "." + s,
                        "1.2.840.10008.1.2.1", file, 1000 + i));
            }
        }
        return new ReceivedStudy(STUDY, "IU_TO_B", destination, Set.of("ORTHANC_A"), instances, clock.instant(),
                clock.instant());
    }

    private void uploadAll() {
        for (FakeTransport.SentFile f : List.copyOf(transport.files)) {
            orchestrator.onTransportEvent(new TransportEvent.FileSent(f.peer(), f.fileId()));
        }
    }

    @Test
    void nominalSendGoesFromReceptionToAnnouncement() throws IOException {
        ReceivedStudy study = study("box-b", 2, 2);

        orchestrator.handleStudy(study);

        Transfer packaged = transfers.only();
        assertThat(packaged.state()).isEqualTo(TransferState.PACKAGED);
        assertThat(packaged.partCount()).isEqualTo(1);
        assertThat(transport.files).singleElement().satisfies(f -> {
            assertThat(f.peer()).isEqualTo(new PeerId(5));
            assertThat(f.file()).exists().startsWith(dir.resolve("outbox").resolve(packaged.id().toString()));
        });
        assertThat(study.instances()).allSatisfy(i -> assertThat(i.file()).doesNotExist());
        assertThat(dir.resolve("incoming/IU_TO_B/" + STUDY)).as("dossiers vides supprimés").doesNotExist();

        uploadAll();

        Transfer announced = transfers.only();
        assertThat(announced.state()).isEqualTo(TransferState.ANNOUNCED);
        assertThat(transport.messages).singleElement().satisfies(m -> {
            assertThat(m.peer()).isEqualTo(new PeerId(5));
            TransferAnnouncement a = messages.decodeAnnouncement(m.text()).orElseThrow();
            assertThat(a.transferId()).isEqualTo(announced.id());
            assertThat(a.packageGroupId()).isEqualTo(announced.packageGroupId());
            assertThat(a.studyInstanceUid()).isEqualTo(STUDY);
            TransferPart part = transfers.parts(announced.id()).getFirst();
            assertThat(a.parts()).containsExactly(new TransferAnnouncement.Part(1, 1,
                    part.file().getFileName().toString(), part.size(), part.sha256()));
        });
    }

    @Test
    void multiPartTransferIsAnnouncedOnlyWhenAllPartsAreUploaded() throws IOException {
        orchestrator = orchestrator(new PackagingConfig(2500, 3)); // une série par paquet
        orchestrator.handleStudy(study("box-b", 2, 2));
        assertThat(transport.files).hasSize(2);

        FakeTransport.SentFile first = transport.files.getFirst();
        orchestrator.onTransportEvent(new TransportEvent.FileSent(first.peer(), first.fileId()));
        orchestrator.onTransportEvent(new TransportEvent.FileSent(first.peer(), first.fileId())); // doublon
        assertThat(transfers.only().state()).isEqualTo(TransferState.PACKAGED);
        assertThat(transport.messages).isEmpty();

        FakeTransport.SentFile second = transport.files.get(1);
        orchestrator.onTransportEvent(new TransportEvent.FileSent(second.peer(), second.fileId()));

        assertThat(transfers.only().state()).isEqualTo(TransferState.ANNOUNCED);
        assertThat(messages.decodeAnnouncement(transport.messages.getFirst().text()).orElseThrow().parts())
                .extracting(TransferAnnouncement.Part::index).containsExactly(1, 2);
    }

    @Test
    void unpairedDestinationFails() throws IOException {
        orchestrator.handleStudy(study("box-z", 1, 1));

        Transfer transfer = transfers.only();
        assertThat(transfer.state()).isEqualTo(TransferState.FAILED);
        assertThat(transfer.errorMessage()).contains("non appairé").contains("box-z");
        assertThat(transport.files).isEmpty();
    }

    @Test
    void packagingFailureFails() throws IOException {
        ReceivedStudy study = study("box-b", 1, 1);
        Files.delete(study.instances().getFirst().file());

        orchestrator.handleStudy(study);

        assertThat(transfers.only().state()).isEqualTo(TransferState.FAILED);
        assertThat(transfers.only().errorMessage()).startsWith("Empaquetage impossible");
    }

    @Test
    void transportFailureWhileHandingOverFails() throws IOException {
        transport.failSendFile = true;

        orchestrator.handleStudy(study("box-b", 1, 1));

        assertThat(transfers.only().state()).isEqualTo(TransferState.FAILED);
        assertThat(transfers.only().errorMessage()).contains("paquet 1/1").contains("XFTP");
    }

    @Test
    void announcementFailureFails() throws IOException {
        transport.failSendMessage = true;
        orchestrator.handleStudy(study("box-b", 1, 1));

        uploadAll();

        assertThat(transfers.only().state()).isEqualTo(TransferState.FAILED);
        assertThat(transfers.only().errorMessage()).startsWith("Annonce impossible");
    }

    @Test
    void transportReportedFailureFails() throws IOException {
        orchestrator.handleStudy(study("box-b", 1, 1));
        long fileId = transport.files.getFirst().fileId();

        orchestrator.onTransportEvent(new TransportEvent.FileFailed(fileId, "envoi : quota"));
        Transfer failed = transfers.only();
        orchestrator.onTransportEvent(new TransportEvent.FileFailed(fileId, "autre")); // déjà en échec : ignoré

        assertThat(failed.state()).isEqualTo(TransferState.FAILED);
        assertThat(transfers.only().errorMessage()).isEqualTo("Transport : envoi : quota");
    }

    @Test
    void eventsForUnknownFilesOrOtherComponentsAreIgnored() throws IOException {
        orchestrator.handleStudy(study("box-b", 1, 1));

        orchestrator.onTransportEvent(new TransportEvent.FileSent(new PeerId(5), 999));
        orchestrator.onTransportEvent(new TransportEvent.FileFailed(999, "x"));
        orchestrator.onTransportEvent(new TransportEvent.MessageReceived(new PeerId(5), "bonjour"));

        assertThat(transfers.only().state()).isEqualTo(TransferState.PACKAGED);
    }

    @Test
    void uploadEventAfterFailureDoesNotRevive() throws IOException {
        orchestrator.handleStudy(study("box-b", 1, 1));
        FakeTransport.SentFile f = transport.files.getFirst();
        orchestrator.onTransportEvent(new TransportEvent.FileFailed(f.fileId(), "x"));

        orchestrator.onTransportEvent(new TransportEvent.FileSent(f.peer(), f.fileId()));

        assertThat(transfers.only().state()).isEqualTo(TransferState.FAILED);
        assertThat(transport.messages).isEmpty();
    }

    @Test
    void studySignalledAgainWhileReceivedResumesTheSameTransfer() throws IOException {
        ReceivedStudy study = study("box-b", 1, 1);
        Transfer pending = Transfer.received(STUDY, "IU_TO_B", "box-b", 1001, clock.instant());
        transfers.create(pending);

        orchestrator.handleStudy(study);

        assertThat(transfers.only().id()).isEqualTo(pending.id());
        assertThat(transfers.only().state()).isEqualTo(TransferState.PACKAGED);
    }

    @Test
    void studySignalledAgainAfterPackagingOnlyCleansUp() throws IOException {
        orchestrator.handleStudy(study("box-b", 1, 1));
        ReceivedStudy leftover = study("box-b", 1, 1); // fichiers revenus (suppression ratée, par exemple)

        orchestrator.handleStudy(leftover);

        assertThat(transfers.transfers).hasSize(1);
        assertThat(transport.files).hasSize(1);
        assertThat(leftover.instances()).allSatisfy(i -> assertThat(i.file()).doesNotExist());
    }

    @Test
    void sharedDirectoriesAreKept() throws IOException {
        ReceivedStudy study = study("box-b", 1, 1);
        Path other = Files.writeString(study.instances().getFirst().file().resolveSibling("autre.dcm"), "x");

        orchestrator.handleStudy(study);

        assertThat(other).exists();
    }

    @Test
    void recoveryResumesInterruptedTransfers() throws IOException {
        // Transfert empaqueté dont un seul paquet avait été confié au transport avant l'arrêt.
        Transfer packaged = Transfer.received("2.25.1", "IU_TO_B", "box-b", 10, clock.instant()).packaged("g1", 2,
                clock.instant());
        transfers.create(packaged);
        Path p1 = Files.writeString(dir.resolve("p1.tar.zst"), "1");
        Path p2 = Files.writeString(dir.resolve("p2.tar.zst"), "2");
        transfers.saveParts(packaged.id(), List.of(
                new TransferPart(packaged.id(), 1, 2, p1, 1, "a".repeat(64), 7L, false),
                new TransferPart(packaged.id(), 2, 2, p2, 1, "b".repeat(64), null, false)));
        // Transfert déposé mais pas encore annoncé.
        Transfer uploaded = Transfer.received("2.25.2", "IU_TO_B", "box-b", 10, clock.instant()).packaged("g2", 1,
                clock.instant()).moveTo(TransferState.UPLOADED, clock.instant());
        transfers.create(uploaded);
        transfers.saveParts(uploaded.id(), List.of(new TransferPart(uploaded.id(), 1, 1, p1, 1, "c".repeat(64), 8L,
                true)));
        // Transfert reçu, en attente de l'entrée DICOM.
        transfers.create(Transfer.received("2.25.3", "IU_TO_B", "box-b", 10, clock.instant()));

        orchestrator.recover();

        assertThat(transport.files).singleElement().satisfies(f -> assertThat(f.file()).isEqualTo(p2));
        assertThat(transfers.find(uploaded.id()).orElseThrow().state()).isEqualTo(TransferState.ANNOUNCED);
        assertThat(transfers.find(packaged.id()).orElseThrow().state()).isEqualTo(TransferState.PACKAGED);
    }

    @Test
    void recoveryCompletesTransferWhoseUploadsAllFinished() throws IOException {
        Transfer packaged = Transfer.received("2.25.1", "IU_TO_B", "box-b", 10, clock.instant()).packaged("g1", 1,
                clock.instant());
        transfers.create(packaged);
        transfers.saveParts(packaged.id(), List.of(new TransferPart(packaged.id(), 1, 1,
                Files.writeString(dir.resolve("p.tar.zst"), "1"), 1, "a".repeat(64), 7L, true)));

        orchestrator.recover();

        assertThat(transfers.find(packaged.id()).orElseThrow().state()).isEqualTo(TransferState.ANNOUNCED);
    }

    @Test
    void recoveryWithNothingToDo() {
        orchestrator.recover();

        assertThat(transport.files).isEmpty();
    }

    @Test
    void announcementFailsWhenPeerWasRemoved() throws IOException {
        orchestrator.handleStudy(study("box-b", 1, 1));
        peers.peers.clear();

        uploadAll();

        assertThat(transfers.only().state()).isEqualTo(TransferState.FAILED);
        assertThat(transfers.only().errorMessage()).contains("non appairé");
    }

    @Test
    void startSubscribesAndRecovers() throws IOException {
        orchestrator.start();
        orchestrator.submit(study("box-b", 1, 1));
        assertThat(transport.listeners).hasSize(1);

        FakeTransport.SentFile f = transport.files.getFirst();
        transport.emit(new TransportEvent.FileSent(f.peer(), f.fileId()));

        assertThat(transfers.only().state()).isEqualTo(TransferState.ANNOUNCED);
    }

    @Test
    void failingStepDoesNotStopTheOrchestrator() throws IOException {
        InMemoryRepositories.Transfers broken = new InMemoryRepositories.Transfers() {
            @Override
            public java.util.Optional<Transfer> findActive(String study, String calledAet) {
                throw new IllegalStateException("base indisponible");
            }
        };
        SendOrchestrator fragile = new SendOrchestrator(broken, peers, new StudyPackager(PackagingConfig.defaults()),
                transport, dir.resolve("outbox"), Runnable::run, clock);

        fragile.submit(study("box-b", 1, 1)); // l'erreur est journalisée, pas propagée

        assertThat(broken.transfers).isEmpty();
    }

    // --- Accusé de bout en bout ---

    private static final PeerId BOX_B = new PeerId(5);

    private Transfer announcedTransfer() throws IOException {
        orchestrator.handleStudy(study("box-b", 2, 2));
        uploadAll();
        Transfer transfer = transfers.only();
        assertThat(transfer.state()).isEqualTo(TransferState.ANNOUNCED);
        return transfer;
    }

    private TransferAck ack(Transfer transfer, TransferAck.Result result, String reason) {
        TransferAnnouncement a = messages.decodeAnnouncement(transport.messages.getLast().text()).orElseThrow();
        return new TransferAck(transfer.id(), transfer.packageGroupId(), result,
                result == TransferAck.Result.RESTORED ? 4 : 0,
                TransferMessages.partsDigest(a.packageGroupId(), a.parts()), reason);
    }

    private void receive(PeerId from, TransferAck ack) {
        orchestrator.onTransportEvent(new TransportEvent.MessageReceived(from, messages.encode(ack)));
    }

    private Path outboxOf(Transfer transfer) {
        return dir.resolve("outbox").resolve(transfer.id().toString());
    }

    @Test
    void restoredAckAcknowledgesTheTransferAndEmptiesItsOutbox() throws IOException {
        Transfer transfer = announcedTransfer();
        assertThat(outboxOf(transfer)).isNotEmptyDirectory();

        receive(BOX_B, ack(transfer, TransferAck.Result.RESTORED, null));

        Transfer acknowledged = transfers.only();
        assertThat(acknowledged.state()).isEqualTo(TransferState.ACKNOWLEDGED);
        assertThat(acknowledged.errorMessage()).isNull();
        assertThat(outboxOf(transfer)).doesNotExist();
        assertThat(dir.resolve("outbox")).as("les autres envois restent").exists();
    }

    @Test
    void failedAckFailsTheTransferAndKeepsItsPackages() throws IOException {
        Transfer transfer = announcedTransfer();

        receive(BOX_B, ack(transfer, TransferAck.Result.FAILED, "Restitution impossible :\nPACS injoignable"));

        assertThat(transfers.only().state()).isEqualTo(TransferState.FAILED);
        assertThat(transfers.only().errorMessage())
                .isEqualTo("Échec chez le destinataire : Restitution impossible : PACS injoignable");
        assertThat(outboxOf(transfer)).isNotEmptyDirectory();
    }

    @Test
    void ackWithOtherPackagesFailsTheTransfer() throws IOException {
        Transfer transfer = announcedTransfer();
        TransferAck good = ack(transfer, TransferAck.Result.RESTORED, null);

        receive(BOX_B, new TransferAck(good.transferId(), good.packageGroupId(), good.result(), 4, "0".repeat(64),
                null));

        assertThat(transfers.only().state()).isEqualTo(TransferState.FAILED);
        assertThat(transfers.only().errorMessage()).startsWith("Accusé du destinataire incohérent");
        assertThat(outboxOf(transfer)).isNotEmptyDirectory();
    }

    @Test
    void ackWithOtherGroupFailsTheTransfer() throws IOException {
        Transfer transfer = announcedTransfer();
        TransferAck good = ack(transfer, TransferAck.Result.RESTORED, null);

        receive(BOX_B, new TransferAck(good.transferId(), "autre-groupe", good.result(), 4, good.partsDigest(), null));

        assertThat(transfers.only().state()).isEqualTo(TransferState.FAILED);
    }

    @Test
    void ackFromAnotherContactIsIgnored() throws IOException {
        Transfer transfer = announcedTransfer();
        peers.save(new Peer("box-c", 6, "box-c", clock.instant()));
        TransferAck ack = ack(transfer, TransferAck.Result.RESTORED, null);

        receive(new PeerId(66), ack); // non appairé
        receive(new PeerId(6), ack);  // appairé, mais pas destinataire de ce transfert

        assertThat(transfers.only().state()).isEqualTo(TransferState.ANNOUNCED);
        assertThat(outboxOf(transfer)).isNotEmptyDirectory();
    }

    @Test
    void ackForUnknownTransferIsIgnored() throws IOException {
        Transfer transfer = announcedTransfer();
        TransferAck ack = ack(transfer, TransferAck.Result.RESTORED, null);

        receive(BOX_B, new TransferAck(java.util.UUID.randomUUID(), ack.packageGroupId(), ack.result(), 4,
                ack.partsDigest(), null));
        receive(BOX_B, new TransferAck(null, ack.packageGroupId(), ack.result(), 4, ack.partsDigest(), null));

        assertThat(transfers.only().state()).isEqualTo(TransferState.ANNOUNCED);
    }

    @Test
    void ackOfFinishedTransferIsIgnored() throws IOException {
        Transfer transfer = announcedTransfer();
        TransferAck restored = ack(transfer, TransferAck.Result.RESTORED, null);
        receive(BOX_B, restored);

        receive(BOX_B, restored);
        receive(BOX_B, ack(transfer, TransferAck.Result.FAILED, "trop tard"));

        assertThat(transfers.only().state()).isEqualTo(TransferState.ACKNOWLEDGED);
    }

    @Test
    void otherMessagesAreNotAcks() throws IOException {
        Transfer transfer = announcedTransfer();

        orchestrator.onTransportEvent(new TransportEvent.MessageReceived(BOX_B, "bonjour"));
        orchestrator.onTransportEvent(new TransportEvent.MessageReceived(BOX_B, transport.messages.getLast().text()));

        assertThat(transfers.find(transfer.id()).orElseThrow().state()).isEqualTo(TransferState.ANNOUNCED);
    }

    @Test
    void recoveryEmptiesTheOutboxOfAcknowledgedTransfers() throws IOException {
        Transfer leftover = announcedTransfer();
        transfers.update(leftover.moveTo(TransferState.ACKNOWLEDGED, clock.instant())); // arrêt avant le ménage
        Transfer clean = Transfer.received("2.25.10", "IU_TO_B", "box-b", 1, clock.instant());
        transfers.create(clean);
        transfers.update(clean.packaged("g", 1, clock.instant()).moveTo(TransferState.UPLOADED, clock.instant())
                .moveTo(TransferState.ANNOUNCED, clock.instant()).moveTo(TransferState.ACKNOWLEDGED, clock.instant()));

        orchestrator.recover();

        assertThat(outboxOf(leftover)).doesNotExist();
        assertThat(outboxOf(clean)).doesNotExist();
    }

    @Test
    void reasonFromPartnerIsSanitized() {
        assertThat(SendOrchestrator.sanitize(null)).isEqualTo("cause non précisée");
        assertThat(SendOrchestrator.sanitize("  ")).isEqualTo("cause non précisée");
        assertThat(SendOrchestrator.sanitize("a\u0007b\r\n")).isEqualTo("a b");
        assertThat(SendOrchestrator.sanitize("x".repeat(600))).hasSize(TransferAck.MAX_REASON);
    }

    // --- Relance ---

    private Transfer failedAtDestination() throws IOException {
        Transfer transfer = announcedTransfer();
        receive(BOX_B, ack(transfer, TransferAck.Result.FAILED, "PACS injoignable"));
        assertThat(transfers.only().state()).isEqualTo(TransferState.FAILED);
        return transfer;
    }

    @Test
    void retryResendsThePackagesAndAnnouncesAgain() throws IOException {
        Transfer transfer = failedAtDestination();
        int sentBefore = transport.files.size();

        Transfer retried = orchestrator.retry(transfer.id()).join();

        assertThat(retried.state()).isEqualTo(TransferState.PACKAGED);
        assertThat(retried.errorMessage()).isNull();
        assertThat(transport.files).hasSize(2 * sentBefore);
        uploadAll();
        assertThat(transfers.only().state()).isEqualTo(TransferState.ANNOUNCED);
        assertThat(transport.messages).hasSize(2);
        assertThat(messages.decodeAnnouncement(transport.messages.getLast().text()).orElseThrow().transferId())
                .isEqualTo(transfer.id());

        receive(BOX_B, ack(transfer, TransferAck.Result.RESTORED, null));
        assertThat(transfers.only().state()).isEqualTo(TransferState.ACKNOWLEDGED);
    }

    @Test
    void retryIsRefusedWhenNotPossible() throws IOException {
        assertThat(orchestrator.retry(java.util.UUID.randomUUID()))
                .failsWithin(java.time.Duration.ofSeconds(1)).withThrowableThat()
                .withCauseInstanceOf(java.util.NoSuchElementException.class);

        Transfer announced = announcedTransfer();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.doRetry(announced.id()))
                .isInstanceOf(RetryRefusedException.class).hasMessageContaining("ANNOUNCED");

        Transfer early = Transfer.received("2.25.11", "IU_TO_B", "box-b", 1, clock.instant());
        transfers.create(early);
        transfers.update(early.failed("Empaquetage impossible", clock.instant()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.doRetry(early.id()))
                .isInstanceOf(RetryRefusedException.class).hasMessageContaining("avant l'empaquetage");
    }

    @Test
    void retryIsRefusedWhenAPackageIsGone() throws IOException {
        Transfer transfer = failedAtDestination();
        Files.delete(transfers.parts(transfer.id()).getFirst().file());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.doRetry(transfer.id()))
                .isInstanceOf(RetryRefusedException.class).hasMessageContaining("absent du dossier d'envoi");
        assertThat(transfers.only().state()).isEqualTo(TransferState.FAILED);
    }

    @Test
    void failedPackagingWithoutPartsCannotBeRetried() {
        Transfer packagedNoParts = Transfer.received("2.25.12", "IU_TO_B", "box-b", 1, clock.instant());
        transfers.create(packagedNoParts);
        transfers.update(packagedNoParts.packaged("g", 1, clock.instant()).failed("x", clock.instant()));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> orchestrator.doRetry(packagedNoParts.id()))
                .isInstanceOf(RetryRefusedException.class);
    }
}
