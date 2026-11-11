package com.imagunity.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import com.imagunity.domain.exam.StudyRestorer;
import com.imagunity.domain.inbound.InboundFile;
import com.imagunity.domain.inbound.InboundTransfer;
import com.imagunity.domain.peer.Peer;
import com.imagunity.packaging.PackagePart;
import com.imagunity.packaging.PackageReader;
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
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiveOrchestratorTest {

    private static final String STUDY = "2.25.77";
    private static final PeerId SENDER = new PeerId(9);

    @TempDir
    Path dir;

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
    private final InMemoryRepositories.Inbound inbound = new InMemoryRepositories.Inbound();
    private final InMemoryRepositories.Peers peers = new InMemoryRepositories.Peers();
    private final FakeTransport transport = new FakeTransport();
    private final TransferMessages messages = new TransferMessages();
    private final List<List<StudyRestorer.Instance>> restored = new ArrayList<>();
    private final List<Path> restoredFilesSeen = new ArrayList<>();
    private IOException restoreFailure;
    private ReceiveOrchestrator orchestrator;
    private long nextFileId = 500;

    @BeforeEach
    void setUp() {
        peers.save(new Peer("box-a", SENDER.value(), "box-a", clock.instant()));
        orchestrator = orchestrator(1L << 30);
    }

    private ReceiveOrchestrator orchestrator(long maxFileSize) {
        StudyRestorer pacs = (study, instances) -> {
            if (restoreFailure != null) {
                throw restoreFailure;
            }
            instances.forEach(i -> assertThat(i.file()).exists());
            restored.add(instances);
            instances.forEach(i -> restoredFilesSeen.add(i.file()));
        };
        return new ReceiveOrchestrator(inbound, peers, transport, new PackageReader(1L << 30), pacs,
                dir.resolve("inbox"), maxFileSize, Runnable::run, clock);
    }

    /** Paquets produits par un vrai empaqueteur, comme chez l'émetteur. */
    private List<PackagePart> packages(int series, long maxPartSize) throws IOException {
        List<ReceivedInstance> instances = new ArrayList<>();
        for (int s = 1; s <= series; s++) {
            Path seriesDir = Files.createDirectories(dir.resolve("sender/" + s));
            for (int i = 1; i <= 2; i++) {
                String sop = STUDY + "." + s + "." + i;
                Path file = Files.write(seriesDir.resolve(sop + ".dcm"), (sop + " données").getBytes());
                instances.add(new ReceivedInstance("1.2", sop, STUDY + "." + s, "1.2", file, Files.size(file)));
            }
        }
        ReceivedStudy study = new ReceivedStudy(STUDY, "IU_TO_B", "box-b", Set.of(), instances, clock.instant(),
                clock.instant());
        return new StudyPackager(new PackagingConfig(maxPartSize, 3)).pack(study, dir.resolve("sender-out"));
    }

    private TransferAnnouncement announcement(List<PackagePart> parts) {
        return new TransferAnnouncement(UUID.randomUUID(), parts.getFirst().manifest().packageGroupId(), STUDY,
                parts.stream().map(p -> new TransferAnnouncement.Part(p.index(), p.count(),
                        p.file().getFileName().toString(), p.size(), p.sha256())).toList());
    }

    /** Le partenaire propose le fichier ; renvoie son identifiant. */
    private long offer(PackagePart part) {
        long fileId = nextFileId++;
        orchestrator.onTransportEvent(new TransportEvent.FileOffered(SENDER, fileId,
                part.file().getFileName().toString(), part.size()));
        return fileId;
    }

    /** Le transport a fini le téléchargement : le fichier est à l'emplacement demandé. */
    private void download(long fileId, PackagePart part) throws IOException {
        Path target = transport.downloads.get(fileId);
        Files.copy(part.file(), target);
        orchestrator.onTransportEvent(new TransportEvent.FileReceived(SENDER, fileId, target));
    }

    private void announce(TransferAnnouncement a) {
        orchestrator.onTransportEvent(new TransportEvent.MessageReceived(SENDER, messages.encode(a)));
    }

    private InboundTransfer onlyTransfer() {
        assertThat(inbound.transfers).hasSize(1);
        return inbound.transfers.values().iterator().next();
    }

    @Test
    void filesThenAnnouncementRestoreTheStudy() throws IOException {
        List<PackagePart> parts = packages(2, PackagingConfig.DEFAULT_MAX_PART_SIZE);
        long fileId = offer(parts.getFirst());
        assertThat(transport.downloads.get(fileId)).startsWithRaw(dir.resolve("inbox").resolve("box-a"));
        download(fileId, parts.getFirst());
        assertThat(restored).isEmpty();

        announce(announcement(parts));

        assertThat(onlyTransfer().state()).isEqualTo(InboundTransfer.State.RESTORED);
        assertThat(restored).singleElement().satisfies(instances -> {
            assertThat(instances).hasSize(4);
            assertThat(instances).extracting(StudyRestorer.Instance::sopInstanceUid)
                    .containsExactlyInAnyOrder(STUDY + ".1.1", STUDY + ".1.2", STUDY + ".2.1", STUDY + ".2.2");
        });
        assertThat(inbound.files.get(fileId).state()).isEqualTo(InboundFile.State.CONSUMED);
        assertThat(transport.downloads.get(fileId)).as("paquet supprimé").doesNotExist();
        assertThat(restoredFilesSeen).allSatisfy(f -> assertThat(f).as("dossier de travail supprimé").doesNotExist());
    }

    @Test
    void announcementThenFilesRestoreTheStudy() throws IOException {
        List<PackagePart> parts = packages(2, 60); // une série par paquet
        assertThat(parts).hasSize(2);
        announce(announcement(parts));
        long first = offer(parts.get(0));
        long second = offer(parts.get(1));

        download(second, parts.get(1));
        assertThat(onlyTransfer().state()).isEqualTo(InboundTransfer.State.ANNOUNCED);
        download(first, parts.get(0));

        assertThat(onlyTransfer().state()).isEqualTo(InboundTransfer.State.RESTORED);
        assertThat(restored.getFirst()).hasSize(4);
    }

    @Test
    void unpairedSenderIsIgnored() throws IOException {
        PackagePart part = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE).getFirst();
        PeerId stranger = new PeerId(66);

        orchestrator.onTransportEvent(new TransportEvent.FileOffered(stranger, 1, part.file().getFileName().toString(),
                part.size()));
        orchestrator.onTransportEvent(new TransportEvent.MessageReceived(stranger,
                messages.encode(announcement(List.of(part)))));

        assertThat(transport.downloads).isEmpty();
        assertThat(inbound.transfers).isEmpty();
    }

    @Test
    void nonConformingFilesAreNotDownloaded() throws IOException {
        orchestrator = orchestrator(1000);
        PackagePart part = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE).getFirst();
        String name = part.file().getFileName().toString();

        orchestrator.onTransportEvent(new TransportEvent.FileOffered(SENDER, 1, "../../evil.tar.zst", 10));
        orchestrator.onTransportEvent(new TransportEvent.FileOffered(SENDER, 2, "photo.jpg", 10));
        orchestrator.onTransportEvent(new TransportEvent.FileOffered(SENDER, 3, name, 1001));
        orchestrator.onTransportEvent(new TransportEvent.FileOffered(SENDER, 4, name, 0));

        assertThat(transport.downloads).isEmpty();
        assertThat(inbound.files).isEmpty();
    }

    @Test
    void duplicateOfferIsIgnored() throws IOException {
        PackagePart part = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE).getFirst();
        String name = part.file().getFileName().toString();

        orchestrator.onTransportEvent(new TransportEvent.FileOffered(SENDER, 1, name, part.size()));
        transport.downloads.clear();
        orchestrator.onTransportEvent(new TransportEvent.FileOffered(SENDER, 1, name, part.size()));

        assertThat(transport.downloads).isEmpty();
    }

    @Test
    void downloadRefusedByTransportFailsTheAnnouncedTransfer() throws IOException {
        List<PackagePart> parts = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE);
        announce(announcement(parts));
        transport.failReceive = true;

        long fileId = offer(parts.getFirst());

        assertThat(inbound.files.get(fileId).state()).isEqualTo(InboundFile.State.FAILED);
        assertThat(onlyTransfer().state()).isEqualTo(InboundTransfer.State.FAILED);
        assertThat(onlyTransfer().errorMessage()).startsWith("Téléchargement impossible");
    }

    @Test
    void transportFailureDuringDownloadFailsTheTransfer() throws IOException {
        List<PackagePart> parts = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE);
        announce(announcement(parts));
        long fileId = offer(parts.getFirst());

        orchestrator.onTransportEvent(new TransportEvent.FileFailed(fileId, "réception : NETWORK"));
        orchestrator.onTransportEvent(new TransportEvent.FileFailed(fileId, "doublon"));
        orchestrator.onTransportEvent(new TransportEvent.FileFailed(999, "inconnu"));

        assertThat(onlyTransfer().state()).isEqualTo(InboundTransfer.State.FAILED);
        assertThat(onlyTransfer().errorMessage()).isEqualTo("Transport : réception : NETWORK");
    }

    @Test
    void fileFailureBeforeAnnouncementOnlyMarksTheFile() throws IOException {
        PackagePart part = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE).getFirst();
        long fileId = offer(part);

        orchestrator.onTransportEvent(new TransportEvent.FileFailed(fileId, "x"));

        assertThat(inbound.files.get(fileId).state()).isEqualTo(InboundFile.State.FAILED);
        assertThat(inbound.transfers).isEmpty();
    }

    @Test
    void packageDifferentFromAnnouncementIsRefused() throws IOException {
        List<PackagePart> parts = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE);
        TransferAnnouncement a = announcement(parts);
        TransferAnnouncement.Part p = a.parts().getFirst();
        TransferAnnouncement forged = new TransferAnnouncement(a.transferId(), a.packageGroupId(), STUDY,
                List.of(new TransferAnnouncement.Part(1, 1, p.fileName(), p.size(), "0".repeat(64))));
        long fileId = offer(parts.getFirst());
        download(fileId, parts.getFirst());

        announce(forged);

        assertThat(onlyTransfer().state()).isEqualTo(InboundTransfer.State.FAILED);
        assertThat(onlyTransfer().errorMessage()).contains("Empreinte du paquet 1/1");
        assertThat(restored).isEmpty();
    }

    @Test
    void manifestDifferentFromAnnouncementIsRefused() throws IOException {
        // Paquet renommé sous un autre groupe : nom, taille et empreinte cohérents avec l'annonce, mais pas le manifeste.
        PackagePart part = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE).getFirst();
        String otherGroup = UUID.randomUUID().toString();
        Path renamed = Files.copy(part.file(), part.file().resolveSibling(otherGroup + "-1-of-1.tar.zst"));
        PackagePart disguised = new PackagePart(renamed, part.manifest(), part.size(), part.sha256());
        TransferAnnouncement a = new TransferAnnouncement(UUID.randomUUID(), otherGroup, STUDY,
                List.of(new TransferAnnouncement.Part(1, 1, renamed.getFileName().toString(), part.size(), part.sha256())));
        download(offer(disguised), disguised);

        announce(a);

        assertThat(onlyTransfer().errorMessage()).contains("Manifeste du paquet 1");
    }

    @Test
    void incompleteStudyIsRefused() throws IOException {
        // Annonce d'un seul des deux paquets d'une étude : il manque des instances.
        List<PackagePart> parts = packages(2, 60);
        PackagePart first = parts.getFirst();
        String group = first.manifest().packageGroupId();
        Path single = Files.copy(first.file(), first.file().resolveSibling(group + "-1-of-1.tar.zst"));
        TransferAnnouncement a = new TransferAnnouncement(UUID.randomUUID(), group, STUDY,
                List.of(new TransferAnnouncement.Part(1, 1, single.getFileName().toString(), first.size(), first.sha256())));
        PackagePart offered = new PackagePart(single, first.manifest(), first.size(), first.sha256());
        download(offer(offered), offered);

        announce(a);

        assertThat(onlyTransfer().errorMessage()).contains("Manifeste du paquet 1");
    }

    @Test
    void pacsFailureFailsTheTransfer() throws IOException {
        restoreFailure = new IOException("PACS injoignable");
        List<PackagePart> parts = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE);
        download(offer(parts.getFirst()), parts.getFirst());

        announce(announcement(parts));

        assertThat(onlyTransfer().state()).isEqualTo(InboundTransfer.State.FAILED);
        assertThat(onlyTransfer().errorMessage()).isEqualTo("Restitution impossible : PACS injoignable");
        assertThat(dir.resolve("inbox/box-a")).isDirectoryNotContaining(p -> p.getFileName().toString().startsWith("restore-"));
    }

    @Test
    void invalidAnnouncementsAreIgnored() throws IOException {
        List<PackagePart> parts = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE);
        TransferAnnouncement good = announcement(parts);
        TransferAnnouncement.Part p = good.parts().getFirst();
        String g = good.packageGroupId();
        List<TransferAnnouncement> invalid = List.of(
                new TransferAnnouncement(null, g, STUDY, good.parts()),
                new TransferAnnouncement(good.transferId(), null, STUDY, good.parts()),
                new TransferAnnouncement(good.transferId(), "pas-un-groupe", STUDY, good.parts()),
                new TransferAnnouncement(good.transferId(), g, "../x", good.parts()),
                new TransferAnnouncement(good.transferId(), g, STUDY, List.of()),
                new TransferAnnouncement(good.transferId(), g, STUDY,
                        List.of(new TransferAnnouncement.Part(1, 2, p.fileName(), p.size(), p.sha256()))),
                new TransferAnnouncement(good.transferId(), g, STUDY,
                        List.of(new TransferAnnouncement.Part(0, 1, p.fileName(), p.size(), p.sha256()))),
                new TransferAnnouncement(good.transferId(), g, STUDY,
                        List.of(new TransferAnnouncement.Part(1, 1, "autre.tar.zst", p.size(), p.sha256()))),
                new TransferAnnouncement(good.transferId(), g, STUDY,
                        List.of(new TransferAnnouncement.Part(1, 1, p.fileName(), 0, p.sha256()))),
                new TransferAnnouncement(good.transferId(), g, STUDY,
                        List.of(new TransferAnnouncement.Part(1, 1, p.fileName(), p.size(), null))),
                new TransferAnnouncement(good.transferId(), g, STUDY,
                        List.of(new TransferAnnouncement.Part(1, 1, p.fileName(), p.size(), "XYZ"))),
                new TransferAnnouncement(good.transferId(), g, STUDY, List.of(
                        new TransferAnnouncement.Part(1, 2, g + "-1-of-2.tar.zst", 1, p.sha256()),
                        new TransferAnnouncement.Part(1, 2, g + "-1-of-2.tar.zst", 1, p.sha256()))));

        invalid.forEach(this::announce);
        orchestrator.onTransportEvent(new TransportEvent.MessageReceived(SENDER, "bonjour"));

        assertThat(inbound.transfers).isEmpty();
        assertThat(ReceiveOrchestrator.validate(good)).isEmpty();
    }

    @Test
    void duplicateAnnouncementIsIgnored() throws IOException {
        List<PackagePart> parts = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE);
        TransferAnnouncement a = announcement(parts);
        download(offer(parts.getFirst()), parts.getFirst());
        announce(a);

        announce(a);

        assertThat(restored).hasSize(1);
    }

    @Test
    void unexpectedCompletionEventsAreIgnored() throws IOException {
        PackagePart part = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE).getFirst();
        long fileId = offer(part);
        orchestrator.onTransportEvent(new TransportEvent.FileReceived(SENDER, 999, dir));
        orchestrator.onTransportEvent(new TransportEvent.FileSent(SENDER, fileId));
        orchestrator.onTransportEvent(new TransportEvent.PeerConnected(SENDER, "box-a"));

        Files.copy(part.file(), transport.downloads.get(fileId));
        orchestrator.onTransportEvent(new TransportEvent.FileReceived(SENDER, fileId, null)); // chemin demandé
        orchestrator.onTransportEvent(new TransportEvent.FileReceived(SENDER, fileId, null)); // doublon

        assertThat(inbound.files.get(fileId).state()).isEqualTo(InboundFile.State.DOWNLOADED);
        assertThat(inbound.files.get(fileId).file()).isEqualTo(transport.downloads.get(fileId));
    }

    @Test
    void recoveryRestoresAnnouncedTransfersWhosePackagesArrived() throws IOException {
        List<PackagePart> parts = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE);
        TransferAnnouncement a = announcement(parts);
        Path inboxFile = Files.createDirectories(dir.resolve("inbox/box-a")).resolve("7-" + a.parts().getFirst().fileName());
        Files.copy(parts.getFirst().file(), inboxFile);
        inbound.saveFile(new InboundFile(7, "box-a", a.parts().getFirst().fileName(), parts.getFirst().size(),
                InboundFile.State.DOWNLOADED, inboxFile, clock.instant()));
        inbound.saveTransfer(new InboundTransfer(a.transferId(), "box-a", a.packageGroupId(), STUDY, 1,
                messages.encode(a), InboundTransfer.State.ANNOUNCED, null, clock.instant(), clock.instant()));

        orchestrator.recover();

        assertThat(onlyTransfer().state()).isEqualTo(InboundTransfer.State.RESTORED);
    }

    @Test
    void recoveryWithNothingPending() {
        orchestrator.recover();

        assertThat(restored).isEmpty();
    }

    @Test
    void startSubscribesToTransportEvents() throws IOException {
        PackagePart part = packages(1, PackagingConfig.DEFAULT_MAX_PART_SIZE).getFirst();
        orchestrator.start();

        transport.emit(new TransportEvent.FileOffered(SENDER, 1, part.file().getFileName().toString(), part.size()));

        assertThat(transport.listeners).hasSize(1);
        assertThat(transport.downloads).containsKey(1L);
    }

    @Test
    void failingStepIsLoggedNotPropagated() {
        // Dépôt des partenaires absent : l'étape échoue, l'erreur est journalisée et rien n'est enregistré.
        ReceiveOrchestrator fragile = new ReceiveOrchestrator(inbound, null, transport, new PackageReader(1),
                (s, i) -> { }, dir, 10, Runnable::run, clock);
        fragile.start();

        transport.emit(new TransportEvent.FileOffered(SENDER, 1, "x", 1));

        assertThat(inbound.files).isEmpty();
    }
}
