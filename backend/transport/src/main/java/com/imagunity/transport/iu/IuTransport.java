package com.imagunity.transport.iu;

import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.protocol.ChunkLayout;
import com.imagunity.protocol.Codec;
import com.imagunity.protocol.FileDescription;
import com.imagunity.protocol.ProtocolException;
import com.imagunity.protocol.SignedMessage;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.System.Logger.Level;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Transport IU-T (spécification {@code docs/specification-protocole.md}, § 13.2) : adaptateur du {@link TransportPort}
 * sur un relais IU-FILE + IU-MSG de l'organisation, à la place de simplex-chat.
 *
 * <ul>
 *   <li><b>Appairage par l'annuaire</b> : l'adresse de la file de chaque box est publiée dans son entrée signée. La
 *       console appaire une box de l'annuaire ({@link #connect} avec cette adresse) ; en face, le premier
 *       message signé d'une box active de l'annuaire crée le contact ({@link TransportEvent.PeerConnected}). Ni lien
 *       d'invitation, ni demande de contact à accepter.</li>
 *   <li><b>Messages</b> : signés par la box, chiffrés pour le destinataire, enregistrés dans une boîte d'envoi puis
 *       déposés dans sa file ({@link IuOutbox}) ; la file de cette box est relevée en continu ({@link IuMailbox}).</li>
 *   <li><b>Fichiers</b> : découpés et téléversés sur le relais ({@link IuFileUploader}) ; la description (clés de
 *       retrait des morceaux) part ensuite par messages, en plusieurs fragments si elle dépasse la taille d'un
 *       message. Le destinataire télécharge, vérifie et réassemble ({@link IuFileDownloader}).</li>
 *   <li><b>Reprise</b> : l'état de chaque envoi et réception est enregistré à chaque morceau ({@link IuStore}) ;
 *       au démarrage ({@link #start()}), les transferts interrompus reprennent où ils en étaient.</li>
 * </ul>
 * Les morceaux ne sont téléchargés que depuis le relais configuré : une description qui désigne un autre serveur est
 * écartée.
 */
public final class IuTransport implements TransportPort {

    private static final System.Logger LOG = System.getLogger(IuTransport.class.getName());

    /** Clé {@code transport} des entrées d'annuaire. */
    public static final String ADDRESS_KIND = "iu";
    static final String CONTACT = "iu.contact";
    static final String TEXT = "iu.text";
    static final String FILE = "iu.file";
    /** Taille d'un fragment de description : un message signé et chiffré reste sous 64 Kio. */
    static final int FRAGMENT = 20_000;
    static final int MAX_FRAGMENTS = 2_000;

    /**
     * Réglages.
     *
     * @param relay     relais de l'organisation
     * @param stateDir  dossier de l'état durable (file, contacts, transferts en cours)
     * @param ttl       durée de séjour demandée pour les morceaux
     * @param parallel  transferts de fichiers simultanés
     * @param keepState durée de conservation de l'état d'un transfert terminé, et d'un message jamais déposé
     * @param early     annonce anticipée (§ 6.6) : la description part dès la création des morceaux, et le
     *                  destinataire télécharge au fil du téléversement
     * @param chunkSize taille de morceau imposée, ou 0 pour l'adapter à la liaison ({@link IuLink})
     */
    public record Settings(URI relay, Path stateDir, Duration ttl, int parallel, Duration keepState, boolean early,
                           int chunkSize) {

        public Settings {
            relay = URI.create(relay.toString().replaceAll("/+$", ""));
            if (parallel < 1) {
                throw new IllegalArgumentException("Au moins un transfert à la fois");
            }
            if (chunkSize != 0 && !ChunkLayout.SIZES.contains(chunkSize)) {
                throw new IllegalArgumentException("Taille de morceau non admise : " + chunkSize
                        + " (256 Kio, 1, 4 ou 8 Mio)");
            }
        }

        /** Taille de morceau adaptée à la liaison. */
        public Settings(URI relay, Path stateDir, Duration ttl, int parallel, Duration keepState, boolean early) {
            this(relay, stateDir, ttl, parallel, keepState, early, 0);
        }

        /** Avec l'annonce anticipée. */
        public Settings(URI relay, Path stateDir, Duration ttl, int parallel, Duration keepState) {
            this(relay, stateDir, ttl, parallel, keepState, true);
        }
    }

    /** Fragment de la description d'un fichier, dans un message {@code iu.file}. */
    record Fragment(String transferId, int part, int parts, String data) {
    }

    private final Settings settings;
    private final UUID boxId;
    private final HybridKeyPair identity;
    private final HybridKemKeyPair keys;
    private final IuDirectory directory;
    private final Clock clock;
    private final Retry retry;
    private final Retry.Sleeper sleeper;
    private final IuStore store;
    private final IuLink link;
    private final IuMsgClient messages;
    private final IuFileUploader uploader;
    private final IuFileDownloader downloader;
    /** Pour un fichier annoncé tôt : un morceau pas encore téléversé est attendu jusqu'à l'expiration du fichier. */
    private final IuFileDownloader patientDownloader;
    private final IuOutbox outbox;
    private final List<Consumer<TransportEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Semaphore transfers;
    private final Set<Thread> workers = ConcurrentHashMap.newKeySet();
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private volatile IuMailbox mailbox;
    private volatile Thread starter;
    private volatile boolean started;
    private volatile boolean closed;
    private int fragmentSize = FRAGMENT;

    public IuTransport(Settings settings, UUID boxId, HybridKeyPair identity, HybridKemKeyPair keys,
                       IuDirectory directory, HttpClient http, Clock clock, Retry retry) {
        this.settings = settings;
        this.boxId = boxId;
        this.identity = identity;
        this.keys = keys;
        this.directory = directory;
        this.clock = clock;
        this.retry = retry;
        this.sleeper = retry.sleeper();
        this.store = new IuStore(settings.stateDir());
        this.link = new IuLink(settings.stateDir());
        this.messages = new IuMsgClient(http, clock);
        this.uploader = new IuFileUploader(new IuFileClient(settings.relay(), http, clock), boxId, identity, clock,
                retry);
        this.downloader = new IuFileDownloader(http, clock, retry);
        this.patientDownloader = new IuFileDownloader(http, clock,
                new Retry(settings.ttl(), retry.maxDelay(), retry.sleeper()));
        this.outbox = new IuOutbox(store, messages, boxId, identity, this::addressOf, clock, settings.keepState());
        // Équitable : les paquets partent dans l'ordre où ils sont confiés (les prioritaires d'abord).
        this.transfers = new Semaphore(settings.parallel(), true);
    }

    /**
     * Démarre le transport : boîte d'envoi, relève de la file (créée au besoin, réessayée tant que le relais est
     * injoignable), reprise des transferts interrompus. À appeler une fois tous les écouteurs abonnés.
     */
    public synchronized void start() {
        if (started || closed) {
            return;
        }
        started = true;
        forgetOld();
        outbox.start();
        starter = Thread.ofVirtual().name("iu-démarrage").start(this::startMailbox);
        for (IuStore.Outgoing outgoing : store.outgoing()) {
            if (!outgoing.announced()) {
                launch(outgoing.fileId(), () -> upload(outgoing));
            }
        }
        for (IuStore.Incoming incoming : store.incoming()) {
            if (incoming.status() == IuStore.Status.DOWNLOADING) {
                launch(incoming.fileId(), () -> download(incoming));
            }
        }
    }

    private void startMailbox() {
        Duration pause = Duration.ofSeconds(1);
        while (!closed) {
            try {
                IuMailbox box = new IuMailbox(messages, queue(), keys,
                        id -> directory.partner(id).map(IuDirectory.Partner::signingKey), this::onMessage, clock);
                if (closed) {
                    return;
                }
                mailbox = box;
                box.start(sleeper);
                LOG.log(Level.INFO, "Relève de la file IU-T sur {0}", settings.relay());
                return;
            } catch (TransportException e) {
                LOG.log(Level.WARNING, "File IU-T indisponible, nouvel essai : {0}", e.getMessage());
                try {
                    sleeper.sleep(pause);
                } catch (InterruptedException interrupted) {
                    return;
                }
                pause = pause.multipliedBy(2).compareTo(IuOutbox.MAX_PAUSE) > 0 ? IuOutbox.MAX_PAUSE
                        : pause.multipliedBy(2);
            }
        }
    }

    private IuMailbox.Queue queue() {
        return queue(true);
    }

    /**
     * File de réception de cette box : créée sur le relais au premier appel, puis relue.
     *
     * <p>Elle est créée avec l'identifiant de dépôt réservé par la box ({@link #contactAddress()}), que son entrée
     * d'annuaire publie. Si le relais le refuse parce que l'entrée en publie un autre, ou aucun (box enrôlée avant de
     * passer à IU-T), la file est créée avec un identifiant tiré par le relais : la demande d'enrôlement suivante porte
     * alors cette adresse, à faire signer par le responsable.
     *
     * @param patient réessayer longuement sur coupure ; sinon un seul essai
     */
    private synchronized IuMailbox.Queue queue(boolean patient) {
        Optional<IuMailbox.Queue> existing = store.queue();
        if (existing.isPresent()) {
            return existing.get();
        }
        IuMailbox.Queue created;
        try {
            created = createQueue(patient, reservedSendId());
        } catch (RelayRefusedException e) {
            if (e.status() != 409) {
                throw e;
            }
            LOG.log(Level.WARNING, "Adresse réservée non publiée dans l''entrée de la box ({0}) : file créée avec un "
                    + "identifiant tiré par le relais, entrée à faire réémettre", e.getMessage());
            created = createQueue(patient, null);
        }
        store.saveQueue(created);
        LOG.log(Level.INFO, "File de réception IU-T créée : {0}", created.address());
        return created;
    }

    private IuMailbox.Queue createQueue(boolean patient, String sendId) {
        Retry.Call<IuMailbox.Queue> create = () -> IuMailbox.create(messages, settings.relay(), boxId, identity,
                sendId);
        if (patient) {
            return retry.run("Création de la file de réception", create);
        }
        try {
            return create.call();
        } catch (IOException e) {
            throw new TransportException("Relais injoignable : " + e.getMessage(), e);
        }
    }

    private synchronized String reservedSendId() {
        return store.reservedSendId().orElseGet(() -> {
            byte[] id = new byte[32];
            new java.security.SecureRandom().nextBytes(id);
            String sendId = Codec.base64Url(id);
            store.saveReservedSendId(sendId);
            return sendId;
        });
    }

    // --- Appairage ---

    @Override
    public String addressKind() {
        return ADDRESS_KIND;
    }

    /**
     * Adresse de la file de réception de cette box, à publier dans sa demande d'enrôlement. Si la file ne peut pas
     * encore être créée (box pas encore enrôlée, relais injoignable), l'adresse qu'elle aura : celle de l'identifiant
     * de dépôt réservé, que le relais n'attribuera qu'à cette box une fois son entrée publiée.
     */
    @Override
    public String contactAddress() {
        try {
            return queue(false).address().toString();
        } catch (TransportException e) {
            LOG.log(Level.INFO, "File IU-T pas encore créée ({0}) : adresse réservée publiée", e.getMessage());
            return new QueueAddress(settings.relay(), reservedSendId()).toString();
        }
    }

    /**
     * Appaire la box de l'annuaire qui publie cette adresse : le contact est créé et annoncé à la box distante, qui le
     * crée à son tour à la réception.
     */
    @Override
    public void connect(String contactAddress) {
        String address;
        try {
            address = QueueAddress.parse(contactAddress).toString();
        } catch (TransportException e) {
            throw new TransportException("Adresse IU-T invalide : " + contactAddress, e);
        }
        IuDirectory.Partner partner = directory.byAddress(address)
                .orElseThrow(() -> new TransportException("Aucune box de l'annuaire ne publie l'adresse " + address));
        if (partner.boxId().equals(boxId)) {
            throw new TransportException("Adresse de cette box");
        }
        byte[] envelope = seal(partner.boxId(), CONTACT, "");
        boolean[] created = new boolean[1];
        IuStore.Contact contact = store.addContact(partner.boxId(), created);
        outbox.enqueue(partner.boxId(), envelope);
        emit(new TransportEvent.PeerConnected(new PeerId(contact.peerId()), partner.displayName()));
    }

    /**
     * Reprise d'un partenaire d'avant la bascule (appairé par SimpleX et lié à une box de l'annuaire) : son contact
     * IU-T, créé au besoin.
     *
     * @return l'identifiant local du contact, à enregistrer pour ce partenaire
     */
    public long adopt(UUID box) {
        return store.addContact(box, new boolean[1]).peerId();
    }

    /** Vrai une fois les partenaires d'avant la bascule repris ({@link #markPeersMigrated()}). */
    public boolean peersMigrated() {
        return store.peersMigrated();
    }

    public void markPeersMigrated() {
        store.markPeersMigrated();
    }

    // --- Messages ---

    @Override
    public void sendMessage(PeerId peer, String text) {
        UUID to = boxOf(peer);
        outbox.enqueue(to, seal(to, TEXT, text));
    }

    private UUID boxOf(PeerId peer) {
        return store.contact(peer.value()).map(IuStore.Contact::boxId)
                .orElseThrow(() -> new TransportException("Contact IU-T inconnu : " + peer));
    }

    /** Scelle un message pour une box active de l'annuaire, avec clés de chiffrement et adresse de file. */
    private byte[] seal(UUID to, String type, String body) {
        IuDirectory.Partner partner = sendable(to);
        try {
            return SignedMessage.of(type, boxId, body, clock.instant()).seal(identity, partner.encryptionKey());
        } catch (ProtocolException e) {
            throw new TransportException("Message pour « " + partner.displayName() + " » : " + e.getMessage(), e);
        }
    }

    private IuDirectory.Partner sendable(UUID to) {
        IuDirectory.Partner partner = directory.partner(to)
                .orElseThrow(() -> new TransportException("Box " + to + " absente de l'annuaire"));
        if (!partner.active()) {
            throw new TransportException("Box « " + partner.displayName() + " » non active dans l'annuaire");
        }
        if (partner.encryptionKey() == null || partner.address() == null) {
            throw new TransportException("Box « " + partner.displayName()
                    + " » sans clés de chiffrement ou sans adresse IU-T dans l'annuaire");
        }
        return partner;
    }

    private Optional<QueueAddress> addressOf(UUID box) {
        return directory.partner(box).filter(IuDirectory.Partner::active).map(IuDirectory.Partner::address)
                .map(QueueAddress::parse);
    }

    /** Message vérifié de la file : contact créé au besoin, puis remis à l'orchestrateur. */
    void onMessage(SignedMessage message) {
        Optional<IuDirectory.Partner> partner = directory.partner(message.boxId());
        if (partner.isEmpty() || !partner.get().active()) {
            LOG.log(Level.WARNING, "Message {0} de la box {1}, non active dans l''annuaire : écarté", message.type(),
                    message.boxId());
            return;
        }
        boolean[] created = new boolean[1];
        IuStore.Contact contact = store.addContact(message.boxId(), created);
        PeerId peer = new PeerId(contact.peerId());
        if (created[0]) {
            LOG.log(Level.INFO, "Nouveau contact IU-T : « {0} » ({1})", partner.get().displayName(),
                    message.boxId());
            emit(new TransportEvent.PeerConnected(peer, partner.get().displayName()));
        }
        switch (message.type()) {
            case CONTACT -> {
                // le contact est créé : rien d'autre à faire
            }
            case TEXT -> emit(new TransportEvent.MessageReceived(peer, message.body()));
            case FILE -> onFragment(contact, message.body());
            default -> LOG.log(Level.WARNING, "Message IU-T de type inconnu {0} : écarté", message.type());
        }
    }

    // --- Envoi de fichiers ---

    @Override
    public long sendFile(PeerId peer, Path file) {
        if (!Files.isRegularFile(file)) {
            throw new TransportException("Fichier à envoyer introuvable : " + file);
        }
        UUID to = boxOf(peer);
        sendable(to);
        long fileId = store.nextId();
        IuStore.Outgoing outgoing = new IuStore.Outgoing(fileId, peer.value(), to, file.toAbsolutePath().toString(),
                null, false, clock.instant());
        store.save(outgoing);
        launch(fileId, () -> upload(outgoing));
        return fileId;
    }

    private void upload(IuStore.Outgoing start) {
        IuStore.Outgoing outgoing = start;
        Path file = Path.of(outgoing.path());
        try {
            transfers.acquire();
            try {
                long size = Files.size(file);
                if (outgoing.state() == null) {
                    int chunkSize = link.chunkSize(size, settings.chunkSize());
                    LOG.log(Level.INFO, "Envoi IU-T {0} : morceaux de {1} Kio (débit estimé : {2})", outgoing.fileId(),
                            chunkSize / ChunkLayout.KIB, link.bytesPerSecond().isPresent()
                                    ? Math.round(link.bytesPerSecond().getAsDouble() / 1024) + " Kio/s" : "inconnu");
                    outgoing = outgoing.with(uploader.prepare(file, chunkSize, settings.ttl()));
                    store.save(outgoing);
                }
                if (settings.early() && !outgoing.described()) {
                    announceFile(outgoing, true);
                    outgoing = outgoing.describedNow();
                    store.save(outgoing);
                }
                IuStore.Outgoing base = outgoing;
                long[] last = {uploadedBytes(outgoing.state()), System.nanoTime()};
                IuFileUploader.UploadState state = uploader.upload(file, outgoing.state(),
                        s -> store.save(base.with(s)),
                        done -> {
                            long now = System.nanoTime();
                            link.record(done - last[0], Duration.ofNanos(now - last[1]), clock.instant());
                            last[0] = done;
                            last[1] = now;
                            emit(new TransportEvent.FileProgress(base.fileId(), TransportEvent.Direction.SENDING,
                                    done, size));
                        });
                outgoing = outgoing.with(state);
                if (!outgoing.described()) {
                    announceFile(outgoing, false);
                }
                store.save(outgoing.announcedNow());
            } finally {
                transfers.release();
            }
            emit(new TransportEvent.FileSent(new PeerId(outgoing.peerId()), outgoing.fileId()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | UncheckedIOException | TransportException | ProtocolException e) {
            if (closed) {
                return; // arrêt de la box : l'envoi reprendra au démarrage
            }
            LOG.log(Level.WARNING, "Envoi IU-T {0} en échec : {1}", outgoing.fileId(), e.getMessage());
            if (outgoing.state() != null) {
                uploader.delete(outgoing.state());
            }
            store.deleteOutgoing(outgoing.fileId());
            emit(new TransportEvent.FileFailed(outgoing.fileId(), e.getMessage()));
        }
    }

    /** Octets déjà téléversés d'un envoi (reprise). */
    private static long uploadedBytes(IuFileUploader.UploadState state) {
        ChunkLayout layout = new ChunkLayout(state.size(), state.chunkSize(), state.chunks().size());
        return state.chunks().stream().filter(IuFileUploader.ChunkState::uploaded)
                .mapToLong(c -> layout.payload(c.index())).sum();
    }

    /** Pour les tests : débit estimé de la liaison. */
    IuLink link() {
        return link;
    }

    /**
     * Envoie la description au destinataire, en fragments.
     *
     * @param early avant la fin du téléversement : en cas d'échec de l'envoi, les morceaux sont retirés du relais et
     *              le destinataire, qui les attendait, reçoit un refus définitif
     */
    private void announceFile(IuStore.Outgoing outgoing, boolean early) {
        String json = IuFileUploader.description(outgoing.state(), early).toJson();
        int parts = (json.length() + fragmentSize - 1) / fragmentSize;
        if (parts > MAX_FRAGMENTS) {
            throw new TransportException("Description du fichier trop longue (" + parts + " fragments)");
        }
        String transferId = outgoing.state().transferId();
        for (int i = 0; i < parts; i++) {
            String data = json.substring(i * fragmentSize, Math.min(json.length(), (i + 1) * fragmentSize));
            outbox.enqueue(outgoing.boxId(), seal(outgoing.boxId(), FILE,
                    Codec.json().writeValueAsString(new Fragment(transferId, i, parts, data))));
        }
    }

    @Override
    public void discardSentFile(long fileId) {
        store.outgoing(fileId).ifPresent(outgoing -> launch(fileId, () -> {
            if (outgoing.state() != null) {
                uploader.delete(outgoing.state());
            }
            store.deleteOutgoing(fileId);
        }));
    }

    // --- Réception de fichiers ---

    private void onFragment(IuStore.Contact contact, String body) {
        Fragment fragment;
        try {
            fragment = Codec.json().readValue(body, Fragment.class);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Fragment de description mal formé de la box {0} : écarté", contact.boxId());
            return;
        }
        if (fragment.transferId() == null || fragment.data() == null || fragment.parts() < 1
                || fragment.parts() > MAX_FRAGMENTS || fragment.part() < 0 || fragment.part() >= fragment.parts()) {
            LOG.log(Level.WARNING, "Fragment de description invalide de la box {0} : écarté", contact.boxId());
            return;
        }
        IuStore.Incoming incoming = store.incoming(contact.boxId(), fragment.transferId()).orElseGet(() ->
                new IuStore.Incoming(store.nextId(), contact.peerId(), contact.boxId(), fragment.transferId(),
                        Collections.nCopies(fragment.parts(), null), null, null, List.of(),
                        IuStore.Status.COLLECTING, clock.instant()));
        if (incoming.status() != IuStore.Status.COLLECTING) {
            return; // description déjà complète : message rejoué
        }
        if (incoming.fragments().size() != fragment.parts()) {
            LOG.log(Level.WARNING, "Fragments incohérents pour le transfert {0} : écarté", fragment.transferId());
            return;
        }
        List<String> fragments = new ArrayList<>(incoming.fragments());
        fragments.set(fragment.part(), fragment.data());
        if (fragments.contains(null)) {
            store.save(new IuStore.Incoming(incoming.fileId(), incoming.peerId(), incoming.boxId(),
                    incoming.transferId(), fragments, null, null, List.of(), IuStore.Status.COLLECTING,
                    incoming.createdAt()));
            return;
        }
        String json = String.join("", fragments);
        FileDescription description;
        try {
            description = FileDescription.fromJson(json);
            checkRelay(description);
        } catch (ProtocolException | TransportException e) {
            LOG.log(Level.WARNING, "Description de fichier de la box {0} écartée : {1}", contact.boxId(),
                    e.getMessage());
            store.deleteIncoming(incoming.fileId());
            return;
        }
        IuStore.Incoming offered = new IuStore.Incoming(incoming.fileId(), incoming.peerId(), incoming.boxId(),
                incoming.transferId(), null, json, null, List.of(), IuStore.Status.OFFERED, incoming.createdAt());
        store.save(offered);
        emit(new TransportEvent.FileOffered(new PeerId(contact.peerId()), offered.fileId(), description.fileName(),
                description.size()));
    }

    /** Les morceaux ne sont téléchargés que depuis le relais de cette box. */
    private void checkRelay(FileDescription description) {
        String relay = settings.relay().toString();
        for (FileDescription.Chunk chunk : description.chunks()) {
            for (FileDescription.Replica replica : chunk.replicas()) {
                if (!replica.relay().replaceAll("/+$", "").equals(relay)) {
                    throw new TransportException("morceau sur un autre relais que " + relay + " : " + replica.relay());
                }
            }
        }
    }

    @Override
    public void receiveFile(long fileId, Path target) {
        IuStore.Incoming incoming = store.incoming(fileId)
                .filter(i -> i.status() != IuStore.Status.COLLECTING)
                .orElseThrow(() -> new TransportException("Fichier IU-T inconnu : " + fileId));
        if (incoming.status() == IuStore.Status.RECEIVED) {
            emit(new TransportEvent.FileReceived(new PeerId(incoming.peerId()), fileId, Path.of(incoming.target())));
            return;
        }
        boolean sameTarget = target.toAbsolutePath().toString().equals(incoming.target());
        IuStore.Incoming downloading = new IuStore.Incoming(fileId, incoming.peerId(), incoming.boxId(),
                incoming.transferId(), null, incoming.description(), target.toAbsolutePath().toString(),
                sameTarget ? incoming.done() : List.of(), IuStore.Status.DOWNLOADING, incoming.createdAt());
        store.save(downloading);
        launch(fileId, () -> download(downloading));
    }

    private void download(IuStore.Incoming start) {
        FileDescription description = FileDescription.fromJson(start.description());
        Path target = Path.of(start.target());
        Set<Integer> done = ConcurrentHashMap.newKeySet();
        done.addAll(start.done());
        try {
            transfers.acquire();
            try {
                Files.createDirectories(target.toAbsolutePath().getParent());
                (description.early() ? patientDownloader : downloader).download(description, target,
                        Set.copyOf(done), index -> {
                    done.add(index);
                    store.save(withDone(start, done));
                }, bytes -> emit(new TransportEvent.FileProgress(start.fileId(), TransportEvent.Direction.RECEIVING,
                        bytes, description.size())));
            } finally {
                transfers.release();
            }
            store.save(withDone(start, done).with(IuStore.Status.RECEIVED));
            emit(new TransportEvent.FileReceived(new PeerId(start.peerId()), start.fileId(), target));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | UncheckedIOException | TransportException | ProtocolException e) {
            if (closed) {
                return; // arrêt de la box : la réception reprendra au démarrage
            }
            LOG.log(Level.WARNING, "Réception IU-T {0} en échec : {1}", start.fileId(), e.getMessage());
            store.save(withDone(start, done).with(IuStore.Status.FAILED));
            emit(new TransportEvent.FileFailed(start.fileId(), e.getMessage()));
        }
    }

    private static IuStore.Incoming withDone(IuStore.Incoming incoming, Set<Integer> done) {
        return new IuStore.Incoming(incoming.fileId(), incoming.peerId(), incoming.boxId(), incoming.transferId(),
                null, incoming.description(), incoming.target(), done.stream().sorted().toList(), incoming.status(),
                incoming.createdAt());
    }

    // --- Événements ---

    @Override
    public void addListener(Consumer<TransportEvent> listener) {
        listeners.add(listener);
    }

    /**
     * Rejoue ce que l'orchestrateur a pu manquer pendant un arrêt de la box : fichiers proposés mais pas encore
     * acceptés, fichiers reçus ou envoyés. Les écouteurs sont idempotents.
     */
    @Override
    public void catchUp() {
        int replayed = 0;
        for (IuStore.Incoming incoming : store.incoming()) {
            PeerId peer = new PeerId(incoming.peerId());
            if (incoming.status() == IuStore.Status.OFFERED) {
                FileDescription description = FileDescription.fromJson(incoming.description());
                emit(new TransportEvent.FileOffered(peer, incoming.fileId(), description.fileName(),
                        description.size()));
                replayed++;
            } else if (incoming.status() == IuStore.Status.RECEIVED) {
                emit(new TransportEvent.FileReceived(peer, incoming.fileId(), Path.of(incoming.target())));
                replayed++;
            }
        }
        for (IuStore.Outgoing outgoing : store.outgoing()) {
            if (outgoing.announced()) {
                emit(new TransportEvent.FileSent(new PeerId(outgoing.peerId()), outgoing.fileId()));
                replayed++;
            }
        }
        LOG.log(Level.INFO, "Rattrapage IU-T : {0} événement(s) rejoué(s)", replayed);
    }

    private void emit(TransportEvent event) {
        for (Consumer<TransportEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "Écouteur du transport en échec sur {0} : {1}", event, e.getMessage());
            }
        }
    }

    // --- Fils et nettoyage ---

    private void launch(long fileId, Runnable work) {
        if (closed || !running.add(fileId)) {
            return;
        }
        Thread thread = Thread.ofVirtual().name("iu-transfert-" + fileId).unstarted(() -> {
            try {
                work.run();
            } catch (RuntimeException e) {
                LOG.log(Level.ERROR, "Transfert IU-T " + fileId + " interrompu", e);
            } finally {
                running.remove(fileId);
                workers.remove(Thread.currentThread());
            }
        });
        workers.add(thread);
        thread.start();
    }

    /** Oublie l'état des transferts terminés ou échoués depuis longtemps (morceaux expirés sur le relais). */
    private void forgetOld() {
        Instant limit = clock.instant().minus(settings.keepState());
        for (IuStore.Incoming incoming : store.incoming()) {
            if (incoming.createdAt().isBefore(limit) && incoming.status() != IuStore.Status.DOWNLOADING) {
                store.deleteIncoming(incoming.fileId());
            }
        }
        for (IuStore.Outgoing outgoing : store.outgoing()) {
            if (outgoing.announced() && outgoing.createdAt().isBefore(limit)) {
                store.deleteOutgoing(outgoing.fileId());
            }
        }
    }

    /** Pour les tests : fragments plus courts. */
    void fragmentSize(int size) {
        this.fragmentSize = size;
    }

    /** Pour les tests : envois et réceptions en cours. */
    boolean busy() {
        return !running.isEmpty();
    }

    IuStore store() {
        return store;
    }

    IuOutbox outbox() {
        return outbox;
    }

    @Override
    public void close() {
        closed = true;
        outbox.close();
        Thread pending = starter;
        if (pending != null) {
            pending.interrupt();
        }
        IuMailbox box = mailbox;
        if (box != null) {
            box.close();
        }
        workers.forEach(Thread::interrupt);
    }
}
