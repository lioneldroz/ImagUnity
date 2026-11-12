package com.imagunity.orchestration;

import com.imagunity.domain.exam.DicomUids;
import com.imagunity.domain.exam.StudyRestorer;
import com.imagunity.domain.inbound.InboundFile;
import com.imagunity.domain.inbound.InboundRepository;
import com.imagunity.domain.inbound.InboundTransfer;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.packaging.PackageManifest;
import com.imagunity.packaging.PackageReader;
import com.imagunity.packaging.UnpackedPackage;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Orchestrateur côté destinataire : reçoit les paquets d'un partenaire, les vérifie et restitue
 * l'étude au PACS local.
 *
 * <pre>
 * fichier proposé ──téléchargement──► fichier reçu ─┐
 * annonce reçue ────────────────────────────────────┴─► tous les paquets là ? ──► vérification ──► restitution
 * </pre>
 * Les fichiers et l'annonce arrivent dans n'importe quel ordre : la restitution est tentée à chaque arrivée.
 * Une fois le transfert restitué ou en échec, un {@link TransferAck accusé} part vers l'émetteur ; s'il n'a pas pu
 * partir, il est renvoyé au redémarrage.
 *
 * <p>Relance par l'émetteur : une nouvelle annonce d'un transfert en échec (même groupe de paquets) ouvre une
 * nouvelle tentative ; les paquets de la tentative ratée ont été écartés à l'échec. Une nouvelle annonce d'un
 * transfert déjà restitué ne déclenche qu'un nouvel accusé, et ses paquets ne sont pas retéléchargés.
 * Une annonce déjà reçue (même date d'émission, rejouée par le rattrapage du transport) est ignorée.
 *
 * <p>Ce qui vient d'un partenaire n'est pas fiable :
 * <ul>
 *   <li>seuls les partenaires appairés sont écoutés ;</li>
 *   <li>un fichier n'est téléchargé que si son nom suit le format des paquets et si sa taille est bornée ;</li>
 *   <li>une annonce mal formée est ignorée ;</li>
 *   <li>chaque paquet doit avoir la taille et l'empreinte SHA-256 annoncées, puis passer les contrôles du
 *       {@link PackageReader} ; son manifeste doit correspondre à l'annonce ;</li>
 *   <li>le {@link StudyRestorer} valide chaque instance avant de l'envoyer au PACS.</li>
 * </ul>
 *
 * <p>Toutes les étapes s'exécutent sur l'{@link Executor} fourni, prévu à un seul fil.
 */
public final class ReceiveOrchestrator {

    private static final System.Logger LOG = System.getLogger(ReceiveOrchestrator.class.getName());
    private static final String UUID_REGEX = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    static final Pattern FILE_NAME = Pattern.compile("(" + UUID_REGEX + ")-([1-9][0-9]{0,3})-of-([1-9][0-9]{0,3})\\.tar\\.zst");
    private static final Pattern GROUP_ID = Pattern.compile(UUID_REGEX);
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final InboundRepository inbound;
    private final PeerRepository peers;
    private final TransportPort transport;
    private final PackageReader reader;
    private final StudyRestorer restorer;
    private final Path inboxDir;
    private final long maxFileSize;
    private final Executor executor;
    private final Clock clock;
    private final TransferMessages messages = new TransferMessages();
    private final PeerAuthorization authorization;

    public ReceiveOrchestrator(InboundRepository inbound, PeerRepository peers, TransportPort transport,
                               PackageReader reader, StudyRestorer restorer, Path inboxDir, long maxFileSize,
                               Executor executor, Clock clock) {
        this(inbound, peers, transport, reader, restorer, inboxDir, maxFileSize, executor, clock,
                PeerAuthorization.allowAll());
    }

    /** @param authorization décide si l'on peut recevoir du partenaire (annuaire) */
    public ReceiveOrchestrator(InboundRepository inbound, PeerRepository peers, TransportPort transport,
                               PackageReader reader, StudyRestorer restorer, Path inboxDir, long maxFileSize,
                               Executor executor, Clock clock, PeerAuthorization authorization) {
        this.authorization = authorization;
        this.inbound = inbound;
        this.peers = peers;
        this.transport = transport;
        this.reader = reader;
        this.restorer = restorer;
        this.inboxDir = inboxDir;
        this.maxFileSize = maxFileSize;
        this.executor = executor;
        this.clock = clock;
    }

    /** S'abonne aux événements du transport, reprend les transferts annoncés et les accusés en attente. */
    public void start() {
        transport.addListener(event -> run(() -> onTransportEvent(event)));
        run(this::recover);
    }

    private void run(Runnable step) {
        executor.execute(() -> {
            try {
                step.run();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Étape de réception en échec", e);
            }
        });
    }

    void onTransportEvent(TransportEvent event) {
        switch (event) {
            case TransportEvent.FileOffered offered -> onFileOffered(offered);
            case TransportEvent.FileReceived received -> onFileReceived(received);
            case TransportEvent.FileFailed failed -> onFileFailed(failed);
            case TransportEvent.MessageReceived message -> onMessage(message);
            default -> {
                // connexion, progression, fin d'envoi : sans objet pour la réception
            }
        }
    }

    // --- Fichiers ---

    private void onFileOffered(TransportEvent.FileOffered offered) {
        Optional<Peer> peer = peers.findByTransportPeerId(offered.peer().value());
        if (peer.isEmpty()) {
            LOG.log(Level.WARNING, "Fichier proposé par un contact non appairé : ignoré");
            return;
        }
        if (refused(peer.get())) {
            return;
        }
        if (!FILE_NAME.matcher(offered.fileName()).matches()) {
            LOG.log(Level.WARNING, "Fichier de {0} au nom non conforme : ignoré", peer.get().name());
            return;
        }
        if (offered.size() <= 0 || offered.size() > maxFileSize) {
            LOG.log(Level.WARNING, "Fichier de {0} de taille non admise ({1} octets) : ignoré", peer.get().name(),
                    offered.size());
            return;
        }
        if (inbound.findFile(offered.fileId()).isPresent()) {
            return;
        }
        if (inbound.findTransferByGroup(peer.get().name(), groupOf(offered.fileName()))
                .filter(t -> t.state() == InboundTransfer.State.RESTORED).isPresent()) {
            LOG.log(Level.INFO, "Paquet {0} d''un transfert déjà restitué : ignoré", offered.fileName());
            return;
        }
        Path target = inboxDir.resolve(peer.get().name()).resolve(offered.fileId() + "-" + offered.fileName());
        InboundFile file = new InboundFile(offered.fileId(), peer.get().name(), offered.fileName(), offered.size(),
                InboundFile.State.DOWNLOADING, target, clock.instant());
        inbound.saveFile(file);
        try {
            Files.createDirectories(target.getParent());
            transport.receiveFile(offered.fileId(), target);
            LOG.log(Level.INFO, "Téléchargement de {0} depuis {1}", offered.fileName(), peer.get().name());
        } catch (IOException | TransportException e) {
            fileFailed(file, "Téléchargement impossible : " + e.getMessage());
        }
    }

    private void onFileReceived(TransportEvent.FileReceived received) {
        Optional<InboundFile> file = inbound.findFile(received.fileId())
                .filter(f -> f.state() == InboundFile.State.DOWNLOADING);
        if (file.isEmpty()) {
            return;
        }
        Path path = received.path() != null ? received.path() : file.get().file();
        inbound.saveFile(file.get().downloaded(path, clock.instant()));
        tryComplete(file.get().peerName(), groupOf(file.get().fileName()));
    }

    private void onFileFailed(TransportEvent.FileFailed failed) {
        inbound.findFile(failed.fileId())
                .filter(f -> f.state() == InboundFile.State.DOWNLOADING)
                .ifPresent(f -> fileFailed(f, "Transport : " + failed.reason()));
    }

    private void fileFailed(InboundFile file, String reason) {
        inbound.saveFile(file.with(InboundFile.State.FAILED, clock.instant()));
        LOG.log(Level.WARNING, "Réception de {0} en échec : {1}", file.fileName(), reason);
        inbound.findTransferByGroup(file.peerName(), groupOf(file.fileName()))
                .filter(t -> t.state() == InboundTransfer.State.ANNOUNCED)
                .ifPresent(t -> fail(t, reason));
    }

    // --- Annonces ---

    private void onMessage(TransportEvent.MessageReceived message) {
        Optional<TransferAnnouncement> announcement = messages.decodeAnnouncement(message.text());
        if (announcement.isEmpty()) {
            return;
        }
        Optional<Peer> peer = peers.findByTransportPeerId(message.peer().value());
        if (peer.isEmpty()) {
            LOG.log(Level.WARNING, "Annonce d'un contact non appairé : ignorée");
            return;
        }
        if (refused(peer.get())) {
            return;
        }
        TransferAnnouncement a = announcement.get();
        Optional<String> invalid = validate(a);
        if (invalid.isPresent()) {
            LOG.log(Level.WARNING, "Annonce de {0} invalide ({1}) : ignorée", peer.get().name(), invalid.get());
            return;
        }
        Optional<InboundTransfer> existing = inbound.findTransfer(peer.get().name(), a.transferId());
        Instant createdAt = clock.instant();
        if (existing.isPresent()) {
            InboundTransfer known = existing.get();
            if (isReplay(known, a)) {
                return; // même annonce, rejouée après un redémarrage
            }
            if (known.state() == InboundTransfer.State.RESTORED) {
                sendAck(known); // l'émetteur n'a pas eu l'accusé : il relance, on le lui renvoie
                return;
            }
            if (known.state() != InboundTransfer.State.FAILED || !known.packageGroupId().equals(a.packageGroupId())) {
                return; // annonce en double, ou relance incohérente
            }
            createdAt = known.createdAt();
            LOG.log(Level.INFO, "Transfert {0} relancé par {1}", a.transferId(), peer.get().name());
        }
        inbound.saveTransfer(new InboundTransfer(a.transferId(), peer.get().name(), a.packageGroupId(),
                a.studyInstanceUid(), a.parts().size(), messages.encode(a), InboundTransfer.State.ANNOUNCED, null,
                createdAt, clock.instant()));
        LOG.log(Level.INFO, "Transfert {0} annoncé par {1} ({2} paquet(s))", a.transferId(), peer.get().name(),
                a.parts().size());
        tryComplete(peer.get().name(), a.packageGroupId());
    }

    /** Vrai si l'annonce reçue est celle déjà enregistrée (même date d'émission, ou sans date des deux côtés). */
    private boolean isReplay(InboundTransfer known, TransferAnnouncement received) {
        return messages.decodeAnnouncement(known.announcement())
                .map(stored -> Objects.equals(stored.announcedAt(), received.announcedAt()))
                .orElse(false);
    }

    static Optional<String> validate(TransferAnnouncement a) {
        if (a.transferId() == null || a.packageGroupId() == null || !GROUP_ID.matcher(a.packageGroupId()).matches()) {
            return Optional.of("identifiants");
        }
        if (!DicomUids.isValid(a.studyInstanceUid())) {
            return Optional.of("Study Instance UID");
        }
        int n = a.parts().size();
        if (n == 0 || n > 9999) {
            return Optional.of("nombre de paquets");
        }
        Set<Integer> indexes = new HashSet<>();
        for (TransferAnnouncement.Part p : a.parts()) {
            boolean ok = p.count() == n && p.index() >= 1 && p.index() <= n && indexes.add(p.index())
                    && (a.packageGroupId() + "-" + p.index() + "-of-" + n + ".tar.zst").equals(p.fileName())
                    && p.size() > 0 && p.sha256() != null && SHA256.matcher(p.sha256()).matches();
            if (!ok) {
                return Optional.of("paquet " + p.index());
            }
        }
        return Optional.empty();
    }

    // --- Restitution ---

    private void tryComplete(String peerName, String groupId) {
        Optional<InboundTransfer> transfer = inbound.findTransferByGroup(peerName, groupId)
                .filter(t -> t.state() == InboundTransfer.State.ANNOUNCED);
        if (transfer.isEmpty()) {
            return;
        }
        TransferAnnouncement a = messages.decodeAnnouncement(transfer.get().announcement()).orElseThrow();
        List<TransferAnnouncement.Part> parts = a.parts().stream()
                .sorted(Comparator.comparingInt(TransferAnnouncement.Part::index)).toList();
        List<InboundFile> files = new ArrayList<>();
        for (TransferAnnouncement.Part part : parts) {
            Optional<InboundFile> file = inbound.findFiles(peerName, part.fileName()).stream()
                    .filter(f -> f.state() == InboundFile.State.DOWNLOADED).findFirst();
            if (file.isEmpty()) {
                return; // en attente d'autres paquets
            }
            files.add(file.get());
        }
        restore(transfer.get(), a, parts, files);
    }

    private void restore(InboundTransfer transfer, TransferAnnouncement a, List<TransferAnnouncement.Part> parts,
                         List<InboundFile> files) {
        Path work = inboxDir.resolve(transfer.peerName()).resolve("restore-" + transfer.transferId());
        int instanceCount;
        try {
            List<StudyRestorer.Instance> instances = new ArrayList<>();
            int expectedInstances = -1;
            for (int i = 0; i < parts.size(); i++) {
                TransferAnnouncement.Part part = parts.get(i);
                Path file = files.get(i).file();
                if (Files.size(file) != part.size() || !sha256(file).equals(part.sha256())) {
                    throw new IOException("Empreinte du paquet " + part.index() + "/" + part.count()
                            + " différente de l'annonce");
                }
                UnpackedPackage unpacked = reader.unpack(file, work.resolve("part-" + part.index()));
                PackageManifest m = unpacked.manifest();
                if (!m.packageGroupId().equals(a.packageGroupId()) || m.partIndex() != part.index()
                        || m.partCount() != part.count() || !m.studyInstanceUid().equals(a.studyInstanceUid())) {
                    throw new IOException("Manifeste du paquet " + part.index() + " différent de l'annonce");
                }
                expectedInstances = m.studyTotals().instances();
                for (UnpackedPackage.Instance instance : unpacked.instances()) {
                    instances.add(new StudyRestorer.Instance(instance.seriesInstanceUid(),
                            instance.entry().sopInstanceUid(), instance.file()));
                }
            }
            if (instances.size() != expectedInstances) {
                throw new IOException("Étude incomplète : " + instances.size() + " instance(s) sur " + expectedInstances);
            }
            restorer.restore(a.studyInstanceUid(), instances);
            instanceCount = instances.size();
        } catch (IOException | RuntimeException e) {
            fail(transfer, "Restitution impossible : " + e.getMessage());
            return;
        } finally {
            deleteRecursively(work);
        }
        InboundTransfer restored = transfer.restored(instanceCount, clock.instant());
        inbound.saveTransfer(restored);
        for (InboundFile file : files) {
            inbound.saveFile(file.with(InboundFile.State.CONSUMED, clock.instant()));
            try {
                Files.deleteIfExists(file.file());
            } catch (IOException e) {
                LOG.log(Level.WARNING, "Paquet reçu non supprimé : " + file.file(), e);
            }
        }
        LOG.log(Level.INFO, "Transfert {0} de {1} restitué au PACS local ({2} instance(s))", transfer.transferId(),
                transfer.peerName(), instanceCount);
        sendAck(restored);
    }

    void recover() {
        List<InboundTransfer> announced = inbound.findTransfers(InboundTransfer.State.ANNOUNCED);
        if (!announced.isEmpty()) {
            LOG.log(Level.INFO, "Reprise : {0} transfert(s) reçu(s) en attente", announced.size());
        }
        announced.forEach(t -> tryComplete(t.peerName(), t.packageGroupId()));
        List<InboundTransfer> awaitingAck = inbound.findAwaitingAck();
        if (!awaitingAck.isEmpty()) {
            LOG.log(Level.INFO, "Reprise : {0} accusé(s) à envoyer", awaitingAck.size());
        }
        awaitingAck.forEach(this::sendAck);
    }

    // --- Accusé ---

    /** Envoie l'accusé d'un transfert terminé ; en cas d'échec du transport, il sera renvoyé au redémarrage. */
    private void sendAck(InboundTransfer transfer) {
        Optional<Peer> peer = peers.findByName(transfer.peerName());
        if (peer.isEmpty()) {
            LOG.log(Level.WARNING, "Accusé du transfert {0} non envoyé : partenaire {1} inconnu",
                    transfer.transferId(), transfer.peerName());
            return;
        }
        TransferAnnouncement a = messages.decodeAnnouncement(transfer.announcement()).orElseThrow();
        boolean restored = transfer.state() == InboundTransfer.State.RESTORED;
        TransferAck ack = new TransferAck(transfer.transferId(), transfer.packageGroupId(),
                restored ? TransferAck.Result.RESTORED : TransferAck.Result.FAILED, transfer.restoredInstances(),
                TransferMessages.partsDigest(a.packageGroupId(), a.parts()),
                restored ? null : truncate(transfer.errorMessage(), TransferAck.MAX_REASON));
        try {
            transport.sendMessage(new PeerId(peer.get().transportPeerId()), messages.encode(ack));
        } catch (TransportException e) {
            LOG.log(Level.WARNING, "Accusé du transfert {0} non envoyé, nouvel essai au redémarrage : {1}",
                    transfer.transferId(), e.getMessage());
            return;
        }
        inbound.saveTransfer(transfer.acknowledged(clock.instant()));
        LOG.log(Level.INFO, "Accusé {0} du transfert {1} envoyé à {2}", ack.result(), transfer.transferId(),
                transfer.peerName());
    }

    static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max);
    }

    // --- Outils ---

    /** Vrai (et journalisé) si l'annuaire interdit de recevoir de ce partenaire. */
    private boolean refused(Peer peer) {
        Optional<String> refusal = authorization.refusal(peer);
        refusal.ifPresent(reason -> LOG.log(Level.WARNING, "Réception refusée : {0}", reason));
        return refusal.isPresent();
    }

    private void fail(InboundTransfer transfer, String reason) {
        InboundTransfer failed = transfer.failed(reason, clock.instant());
        inbound.saveTransfer(failed);
        LOG.log(Level.WARNING, "Transfert {0} de {1} en échec : {2}", transfer.transferId(), transfer.peerName(),
                reason);
        discardFiles(failed);
        sendAck(failed);
    }

    /** Écarte les paquets reçus d'un transfert en échec : une relance repartira de paquets neufs. */
    private void discardFiles(InboundTransfer transfer) {
        TransferAnnouncement a = messages.decodeAnnouncement(transfer.announcement()).orElseThrow();
        for (TransferAnnouncement.Part part : a.parts()) {
            for (InboundFile file : inbound.findFiles(transfer.peerName(), part.fileName())) {
                if (file.state() != InboundFile.State.DOWNLOADED) {
                    continue;
                }
                inbound.saveFile(file.with(InboundFile.State.FAILED, clock.instant()));
                try {
                    Files.deleteIfExists(file.file());
                } catch (IOException e) {
                    LOG.log(Level.WARNING, "Paquet écarté non supprimé : " + file.file(), e);
                }
            }
        }
    }

    private static String groupOf(String fileName) {
        Matcher m = FILE_NAME.matcher(fileName);
        return m.matches() ? m.group(1) : "";
    }

    static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Dossier de travail non supprimé : " + dir, e);
        }
    }
}
