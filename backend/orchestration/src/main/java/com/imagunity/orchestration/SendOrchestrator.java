package com.imagunity.orchestration;

import com.imagunity.domain.exam.ReceivedInstance;
import com.imagunity.domain.exam.ReceivedStudy;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferPart;
import com.imagunity.domain.transfer.TransferRepository;
import com.imagunity.domain.transfer.TransferState;
import com.imagunity.packaging.PackagePart;
import com.imagunity.packaging.StudyPackager;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Orchestrateur côté émetteur : fait avancer chaque transfert dans sa machine à états.
 *
 * <pre>
 * RECEIVED ──empaquetage──► PACKAGED ──dépôt de tous les paquets──► UPLOADED ──annonce──► ANNOUNCED
 *     └──────────────────────────────── échec ────────────────────────────────► FAILED
 * </pre>
 * (L'accusé de bout en bout, {@code ANNOUNCED → ACKNOWLEDGED}, arrive en phase 6.)
 *
 * <p>Chaque étape est enregistrée avant de passer à la suivante. Au démarrage, {@link #start()}
 * reprend les transferts {@code PACKAGED} (paquets pas encore confiés au transport) et
 * {@code UPLOADED} (annonce pas encore envoyée). Un transfert {@code RECEIVED} reprend quand l'entrée
 * DICOM signale à nouveau l'étude, dont les fichiers sont restés dans le dossier de réception.
 *
 * <p>Toutes les étapes s'exécutent sur l'{@link Executor} fourni, prévu à un seul fil : les
 * transferts n'ont pas besoin de verrou et les événements du transport sont traités dans l'ordre.
 */
public final class SendOrchestrator {

    private static final System.Logger LOG = System.getLogger(SendOrchestrator.class.getName());

    private final TransferRepository transfers;
    private final PeerRepository peers;
    private final StudyPackager packager;
    private final TransportPort transport;
    private final Path outboxDir;
    private final Executor executor;
    private final Clock clock;
    private final TransferMessages messages = new TransferMessages();

    public SendOrchestrator(TransferRepository transfers, PeerRepository peers, StudyPackager packager,
                            TransportPort transport, Path outboxDir, Executor executor, Clock clock) {
        this.transfers = transfers;
        this.peers = peers;
        this.packager = packager;
        this.transport = transport;
        this.outboxDir = outboxDir;
        this.executor = executor;
        this.clock = clock;
    }

    /** S'abonne aux événements du transport et reprend les transferts interrompus. */
    public void start() {
        transport.addListener(event -> run(() -> onTransportEvent(event)));
        run(this::recover);
    }

    /** Prend en charge une étude complète reçue du PACS local. */
    public void submit(ReceivedStudy study) {
        run(() -> handleStudy(study));
    }

    private void run(Runnable step) {
        executor.execute(() -> {
            try {
                step.run();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Étape de l'orchestrateur en échec", e);
            }
        });
    }

    // --- Étapes ---

    void handleStudy(ReceivedStudy study) {
        Optional<Transfer> existing = transfers.findActive(study.studyInstanceUid(), study.calledAet());
        Transfer transfer;
        if (existing.isEmpty()) {
            transfer = Transfer.received(study.studyInstanceUid(), study.calledAet(), study.destination(),
                    study.totalSize(), clock.instant());
            transfers.create(transfer);
            LOG.log(Level.INFO, "Transfert {0} créé pour {1}", transfer.id(), transfer.destination());
        } else if (existing.get().state() == TransferState.RECEIVED) {
            transfer = existing.get(); // reprise après redémarrage
            LOG.log(Level.INFO, "Transfert {0} repris", transfer.id());
        } else {
            // Étude déjà empaquetée : les fichiers de réception sont des restes à supprimer.
            LOG.log(Level.INFO, "Étude déjà prise en charge par le transfert {0} ({1})", existing.get().id(),
                    existing.get().state());
            deleteIncoming(study);
            return;
        }
        packageStudy(transfer, study);
    }

    private void packageStudy(Transfer transfer, ReceivedStudy study) {
        List<PackagePart> parts;
        try {
            parts = packager.pack(study, outboxDir.resolve(transfer.id().toString()));
        } catch (IOException | RuntimeException e) {
            fail(transfer, "Empaquetage impossible : " + e.getMessage());
            return;
        }
        List<TransferPart> transferParts = parts.stream()
                .map(p -> new TransferPart(transfer.id(), p.index(), p.count(), p.file(), p.size(), p.sha256(),
                        null, false))
                .toList();
        transfers.saveParts(transfer.id(), transferParts);
        Transfer packaged = transfer.packaged(parts.getFirst().manifest().packageGroupId(), parts.size(),
                clock.instant());
        transfers.update(packaged);
        deleteIncoming(study);
        hand(packaged);
    }

    /** Confie au transport les paquets qui ne l'ont pas encore été. */
    private void hand(Transfer transfer) {
        Optional<PeerId> peer = peerOf(transfer);
        if (peer.isEmpty()) {
            return;
        }
        for (TransferPart part : transfers.parts(transfer.id())) {
            if (part.transportFileId() != null) {
                continue; // déjà confié : le transport poursuit le dépôt de lui-même
            }
            try {
                long fileId = transport.sendFile(peer.get(), part.file());
                transfers.updatePart(part.handedOver(fileId));
            } catch (TransportException e) {
                fail(transfer, "Envoi du paquet " + part.index() + "/" + part.count() + " impossible : "
                        + e.getMessage());
                return;
            }
        }
        completeUploadIfDone(transfer);
    }

    private void completeUploadIfDone(Transfer transfer) {
        List<TransferPart> parts = transfers.parts(transfer.id());
        if (parts.isEmpty() || !parts.stream().allMatch(TransferPart::uploaded)) {
            return;
        }
        Transfer uploaded = transfer.moveTo(TransferState.UPLOADED, clock.instant());
        transfers.update(uploaded);
        announce(uploaded);
    }

    private void announce(Transfer transfer) {
        Optional<PeerId> peer = peerOf(transfer);
        if (peer.isEmpty()) {
            return;
        }
        List<TransferAnnouncement.Part> parts = transfers.parts(transfer.id()).stream()
                .map(p -> new TransferAnnouncement.Part(p.index(), p.count(), p.file().getFileName().toString(),
                        p.size(), p.sha256()))
                .toList();
        TransferAnnouncement announcement = new TransferAnnouncement(transfer.id(), transfer.packageGroupId(),
                transfer.studyInstanceUid(), parts);
        try {
            transport.sendMessage(peer.get(), messages.encode(announcement));
        } catch (TransportException e) {
            fail(transfer, "Annonce impossible : " + e.getMessage());
            return;
        }
        transfers.update(transfer.moveTo(TransferState.ANNOUNCED, clock.instant()));
        LOG.log(Level.INFO, "Transfert {0} annoncé à {1} ({2} paquet(s))", transfer.id(), transfer.destination(),
                parts.size());
    }

    void onTransportEvent(TransportEvent event) {
        switch (event) {
            case TransportEvent.FileSent sent -> transfers.findPartByTransportFileId(sent.fileId()).ifPresent(part -> {
                if (!part.uploaded()) {
                    transfers.updatePart(part.markUploaded());
                }
                transfers.find(part.transferId())
                        .filter(t -> t.state() == TransferState.PACKAGED)
                        .ifPresent(this::completeUploadIfDone);
            });
            case TransportEvent.FileFailed failed -> transfers.findPartByTransportFileId(failed.fileId())
                    .flatMap(part -> transfers.find(part.transferId()))
                    .filter(t -> !t.state().isTerminal())
                    .ifPresent(t -> fail(t, "Transport : " + failed.reason()));
            default -> {
                // Les autres événements (réception, appairage) concernent d'autres composants.
            }
        }
    }

    void recover() {
        List<Transfer> packaged = transfers.findByStates(Set.of(TransferState.PACKAGED));
        List<Transfer> uploaded = transfers.findByStates(Set.of(TransferState.UPLOADED));
        int received = transfers.findByStates(Set.of(TransferState.RECEIVED)).size();
        if (!packaged.isEmpty() || !uploaded.isEmpty() || received > 0) {
            LOG.log(Level.INFO, "Reprise : {0} transfert(s) à déposer, {1} à annoncer, {2} en attente de l''entrée DICOM",
                    packaged.size(), uploaded.size(), received);
        }
        packaged.forEach(this::hand);
        uploaded.forEach(this::announce);
    }

    // --- Outils ---

    private Optional<PeerId> peerOf(Transfer transfer) {
        Optional<Peer> peer = peers.findByName(transfer.destination());
        if (peer.isEmpty()) {
            fail(transfer, "Partenaire non appairé : " + transfer.destination());
            return Optional.empty();
        }
        return Optional.of(new PeerId(peer.get().transportPeerId()));
    }

    private void fail(Transfer transfer, String reason) {
        transfers.update(transfer.failed(reason, clock.instant()));
        LOG.log(Level.WARNING, "Transfert {0} en échec : {1}", transfer.id(), reason);
    }

    /** Supprime les instances reçues (désormais dans les paquets), puis les dossiers devenus vides. */
    private static void deleteIncoming(ReceivedStudy study) {
        Set<Path> seriesDirs = new LinkedHashSet<>();
        Set<Path> studyDirs = new LinkedHashSet<>();
        for (ReceivedInstance instance : study.instances()) {
            try {
                Files.deleteIfExists(instance.file());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Fichier de réception non supprimé : " + instance.file(), e);
            }
            Path series = instance.file().getParent();
            if (series != null) {
                seriesDirs.add(series);
                if (series.getParent() != null) {
                    studyDirs.add(series.getParent());
                }
            }
        }
        // Les séries d'abord : le dossier d'une étude n'est vide qu'une fois toutes ses séries supprimées.
        List<Path> dirs = new java.util.ArrayList<>(seriesDirs);
        dirs.addAll(studyDirs);
        for (Path dir : dirs) {
            try {
                Files.delete(dir);
            } catch (DirectoryNotEmptyException | NoSuchFileException e) {
                // encore utilisé par une autre étude ou série, ou déjà supprimé
            } catch (IOException e) {
                LOG.log(Level.DEBUG, "Dossier de réception non supprimé : " + dir, e);
            }
        }
    }
}
