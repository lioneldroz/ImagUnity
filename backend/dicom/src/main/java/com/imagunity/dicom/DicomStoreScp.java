package com.imagunity.dicom;

import com.imagunity.rules.RoutingRule;
import com.imagunity.domain.exam.DicomUids;
import com.imagunity.domain.exam.ReceivedInstance;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.io.DicomInputStream.IncludeBulkData;
import org.dcm4che3.io.DicomOutputStream;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.AssociationHandler;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.AAssociateAC;
import org.dcm4che3.net.pdu.AAssociateRJ;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.pdu.UserIdentityAC;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.DicomServiceRegistry;

/**
 * Entrée DICOM de la box : un SCP C-STORE (et C-ECHO) sur le réseau local.
 *
 * <p>Pour le PACS, la box est une destination DICOM ordinaire. Contrôles appliqués :
 * <ul>
 *   <li>l'AET appelant doit figurer dans la liste des émetteurs autorisés ;</li>
 *   <li>l'AET appelé doit être une destination configurée (routage par destination DICOM) ;</li>
 *   <li>les UID doivent être valides, et le SOP Instance UID du jeu de données doit
 *       correspondre à celui de la commande ;</li>
 *   <li>l'espace disque libre doit rester au-dessus du seuil configuré.</li>
 * </ul>
 * Les instances sont écrites telles que reçues (aucune modification), puis regroupées par étude.
 */
public final class DicomStoreScp implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(DicomStoreScp.class.getName());

    private final DicomInputConfig config;
    private final IncomingStore store;
    private final StudyAssembler assembler;
    private Device device;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    /**
     * Vrai entre la fin de {@link #start()} et le début de {@link #stop()}. Les dépôts STOW-RS arrivent par le serveur
     * web, qui démarre avant l'entrée DICOM et s'arrête après elle : hors de cette fenêtre, ils sont refusés (à
     * réessayer), sans quoi ils écriraient dans un dossier de réception pas encore prêt ou déjà fermé.
     */
    private volatile boolean accepting;
    /** Dépôts STOW-RS en cours (verrou partagé), attendus par {@link #stop()} (verrou exclusif). */
    private final java.util.concurrent.locks.ReentrantReadWriteLock stowGate =
            new java.util.concurrent.locks.ReentrantReadWriteLock();

    public DicomStoreScp(DicomInputConfig config, StudyListener listener) {
        this(config, listener, Clock.systemUTC());
    }

    /**
     * Limites de l'entrée DICOM, face à un poste du réseau local malveillant ou défaillant.
     *
     * @param maxInstanceSize     plus grande instance acceptée, en octets (C-STORE et STOW-RS)
     * @param maxAssociations     associations DICOM ouvertes en même temps, au plus
     * @param idleTimeout         association sans échange au-delà de ce délai : fermée
     * @param requestTimeout      délai pour recevoir la demande d'association après la connexion
     * @param allowedSources      adresses ou réseaux (CIDR) admis à se connecter ; vide : toutes (l'AET appelant
     *                            reste contrôlé)
     */
    public record Limits(long maxInstanceSize, int maxAssociations, Duration idleTimeout, Duration requestTimeout,
                         java.util.Set<String> allowedSources) {

        public Limits {
            if (maxInstanceSize < 1 || maxAssociations < 1) {
                throw new IllegalArgumentException("Limites DICOM invalides");
            }
            allowedSources = allowedSources == null ? java.util.Set.of() : java.util.Set.copyOf(allowedSources);
            allowedSources.forEach(SourceFilter::parse);
        }

        public static Limits defaults() {
            return new Limits(4L * 1024 * 1024 * 1024, 32, Duration.ofMinutes(2), Duration.ofSeconds(10),
                    java.util.Set.of());
        }
    }

    private volatile Limits limits = Limits.defaults();

    /** Fixe les limites ; à appeler avant {@link #start()}. */
    public void limits(Limits limits) {
        this.limits = java.util.Objects.requireNonNull(limits);
    }

    /** Règles de routage de la configuration centralisée, si elle en fixe ; sinon celles de la configuration. */
    private volatile java.util.function.Supplier<Optional<List<RoutingRule>>> centralRouting = Optional::empty;

    /**
     * Règles de routage fixées hors de la configuration locale (configuration centralisée), relues à chaque instance :
     * un changement s'applique sans redémarrage. Vide : règles de la configuration locale.
     */
    public void centralRouting(java.util.function.Supplier<Optional<List<RoutingRule>>> rules) {
        this.centralRouting = rules;
    }

    DicomStoreScp(DicomInputConfig config, StudyListener listener, Clock clock) {
        this.config = config;
        this.store = new IncomingStore(config.storageDir());
        this.assembler = new StudyAssembler(clock, config.studyIdleTimeout(), listener);
    }

    /** Reprend les études en attente sur le disque, puis ouvre le port DICOM. */
    public synchronized void start() throws IOException {
        if (executor != null) {
            return;
        }
        store.initialize();
        int recovered = 0;
        for (IncomingStore.Recovered r : store.recover()) {
            String destination = r.destination() != null ? r.destination()
                    : config.destinationFor(r.calledAet()).orElse(null);
            if (destination == null || !config.isKnownCalledAet(r.calledAet())) {
                LOG.log(Level.WARNING, "Instances en attente pour la destination {0}, qui n''est plus configurée : ignorées",
                        r.calledAet());
                continue;
            }
            assembler.add(r.calledAet(), destination, null, r.studyInstanceUid(), r.instance());
            recovered++;
        }
        if (recovered > 0) {
            LOG.log(Level.INFO, "{0} instance(s) en attente reprise(s) dans {1} étude(s)", recovered,
                    assembler.pendingStudies());
        }

        // Appareil neuf à chaque démarrage : un redémarrage ne doit pas cumuler connexions et AE.
        device = new Device("imagunity-box");
        Connection connection = new Connection();
        connection.setPort(config.port());
        if (config.bindAddress() != null && !config.bindAddress().isBlank()) {
            connection.setBindAddress(config.bindAddress());
        }
        // Un poste qui ouvre des connexions sans rien envoyer, ou des associations sans fin, ne bloque pas l'entrée.
        connection.setRequestTimeout((int) limits.requestTimeout().toMillis());
        connection.setIdleTimeout((int) limits.idleTimeout().toMillis());
        connection.setReleaseTimeout(10_000);
        ApplicationEntity ae = new ApplicationEntity("*");
        ae.setAssociationAcceptor(true);
        ae.addConnection(connection);
        ae.addTransferCapability(new TransferCapability(null, "*", TransferCapability.Role.SCP, "*"));
        device.addConnection(connection);
        device.addApplicationEntity(ae);
        device.setLimitOpenAssociations(limits.maxAssociations());

        DicomServiceRegistry services = new DicomServiceRegistry();
        services.addDicomService(new BasicCEchoSCP());
        services.addDicomService(new StoreService());
        device.setDimseRQHandler(services);
        device.setAssociationHandler(new AccessControl());

        executor = Executors.newVirtualThreadPerTaskExecutor();
        scheduler = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("dicom-scheduler").factory());
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduler);
        try {
            device.bindConnections();
        } catch (IOException | GeneralSecurityException e) {
            stop(); // libère les fils d'exécution : un nouvel essai de démarrage reste possible
            throw e instanceof IOException io ? io : new IOException("Ouverture du port DICOM impossible", e);
        }
        long period = Math.max(100, Math.min(1000, config.studyIdleTimeout().toMillis() / 4));
        scheduler.scheduleWithFixedDelay(assembler::flushIdle, period, period, TimeUnit.MILLISECONDS);
        LOG.log(Level.INFO, "Entrée DICOM ouverte sur le port {0} ; destinations : {1}", config.port(),
                config.routes().keySet());
        if (config.routingAet() != null) {
            LOG.log(Level.INFO, "Routage par champ DICOM sur {0} : {1}", config.routingAet(),
                    String.join(" ; ", config.rules().stream().map(RoutingRule::describe).toList()));
        }
        accepting = true; // dossier de réception prêt : les dépôts STOW-RS sont admis
    }

    /** Vrai si l'entrée DICOM est démarrée : les dépôts STOW-RS sont admis. */
    public boolean accepting() {
        return accepting;
    }

    /** Attente au plus, à l'arrêt, de la fin des réceptions et des signalements d'études en cours. */
    private volatile Duration stopTimeout = Duration.ofSeconds(10);

    /** Fixe l'attente maximale de {@link #stop()} (tests). */
    void stopTimeout(Duration timeout) {
        this.stopTimeout = java.util.Objects.requireNonNull(timeout);
    }

    /**
     * Ferme le port DICOM. Les études en cours restent sur le disque et seront reprises au démarrage.
     *
     * <p>Les nouveaux dépôts STOW-RS sont refusés (à réessayer), ceux en cours attendus ; les réceptions C-STORE en
     * cours sont interrompues, puis attendues. L'attente dure au plus {@link #stopTimeout(Duration) le délai d'arrêt} :
     * au retour, plus rien n'écrit dans le dossier de réception (sauf dépassement du délai, signalé dans le journal).
     */
    public synchronized void stop() {
        if (executor == null) {
            return;
        }
        accepting = false; // plus de nouveau dépôt STOW-RS
        awaitStowDeposits();
        device.unbindConnections();
        scheduler.shutdownNow();
        executor.shutdownNow();
        awaitTermination(executor, scheduler);
        executor = null;
        scheduler = null;
        LOG.log(Level.INFO, "Entrée DICOM fermée");
    }

    /** Attend la fin des dépôts STOW-RS en cours, au plus le délai d'arrêt. */
    private void awaitStowDeposits() {
        try {
            if (stowGate.writeLock().tryLock(stopTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                stowGate.writeLock().unlock();
            } else {
                LOG.log(Level.WARNING, "Entrée DICOM : des dépôts STOW-RS ne sont pas terminés après {0} ms",
                        stopTimeout.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.log(Level.WARNING, "Entrée DICOM : attente des dépôts STOW-RS interrompue");
        }
    }

    private void awaitTermination(ExecutorService... services) {
        long deadline = System.nanoTime() + stopTimeout.toNanos();
        try {
            for (ExecutorService service : services) {
                if (!service.awaitTermination(deadline - System.nanoTime(), TimeUnit.NANOSECONDS)) {
                    LOG.log(Level.WARNING, "Entrée DICOM : des traitements en cours ne sont pas terminés après {0} ms",
                            stopTimeout.toMillis());
                    return;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // l'appelant veut s'arrêter : on n'attend pas davantage
            LOG.log(Level.WARNING, "Entrée DICOM : attente de l'arrêt interrompue");
        }
    }

    @Override
    public void close() {
        stop();
    }

    /** Études en cours de réception (pas encore complètes). */
    public int pendingStudies() {
        return assembler.pendingStudies();
    }

    /** Refuse les émetteurs non autorisés et les destinations inconnues dès l'association. */
    private final class AccessControl extends AssociationHandler {

        @Override
        protected AAssociateAC makeAAssociateAC(Association as, AAssociateRQ rq, UserIdentityAC userIdentity)
                throws IOException {
            java.net.InetAddress from = as.getSocket().getInetAddress();
            if (!SourceFilter.allows(limits.allowedSources(), from)) {
                LOG.log(Level.WARNING, "Association refusée : adresse {0} hors des sources admises",
                        from.getHostAddress());
                throw new AAssociateRJ(AAssociateRJ.RESULT_REJECTED_PERMANENT, AAssociateRJ.SOURCE_SERVICE_USER,
                        AAssociateRJ.REASON_NO_REASON_GIVEN);
            }
            if (!config.isCallingAetAllowed(rq.getCallingAET())) {
                LOG.log(Level.WARNING, "Association refusée : émetteur {0} non autorisé (depuis {1})",
                        printable(rq.getCallingAET()), from.getHostAddress());
                throw new AAssociateRJ(AAssociateRJ.RESULT_REJECTED_PERMANENT, AAssociateRJ.SOURCE_SERVICE_USER,
                        AAssociateRJ.REASON_CALLING_AET_NOT_RECOGNIZED);
            }
            if (!config.isKnownCalledAet(rq.getCalledAET())) {
                LOG.log(Level.WARNING, "Association refusée : destination {0} inconnue (émetteur {1})",
                        printable(rq.getCalledAET()), printable(rq.getCallingAET()));
                throw new AAssociateRJ(AAssociateRJ.RESULT_REJECTED_PERMANENT, AAssociateRJ.SOURCE_SERVICE_USER,
                        AAssociateRJ.REASON_CALLED_AET_NOT_RECOGNIZED);
            }
            return super.makeAAssociateAC(as, rq, userIdentity);
        }
    }

    /** Réception C-STORE : écriture sur disque, contrôles, puis rattachement à l'étude. */
    private final class StoreService extends BasicCStoreSCP {

        StoreService() {
            super("*");
        }

        @Override
        protected void store(Association as, PresentationContext pc, Attributes rq, PDVInputStream data,
                             Attributes rsp) throws IOException {
            String calledAet = as.getCalledAET().trim();
            if (!config.isKnownCalledAet(calledAet)) {
                throw new DicomServiceException(Status.ProcessingFailure, "Destination inconnue");
            }
            String cuid = rq.getString(Tag.AffectedSOPClassUID);
            String iuid = rq.getString(Tag.AffectedSOPInstanceUID);
            String tsuid = pc.getTransferSyntax();
            if (!DicomUids.isValid(cuid) || !DicomUids.isValid(iuid) || !DicomUids.isValid(tsuid)) {
                throw new DicomServiceException(Status.CannotUnderstand, "UID de commande invalide");
            }
            requireFreeSpace();
            Path temp = store.newTempFile();
            try {
                try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(temp));
                     DicomOutputStream out = new DicomOutputStream(os, UID.ExplicitVRLittleEndian)) {
                    out.writeFileMetaInformation(as.createFileMetaInformation(iuid, cuid, tsuid));
                    data.copyTo(new LimitedOutput(out, limits.maxInstanceSize()));
                }
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(temp);
                if (e instanceof DicomServiceException refused) {
                    throw refused; // espace disque insuffisant en cours de réception : statut gardé
                }
                LOG.log(Level.ERROR, "Échec de la réception d'une instance", e);
                throw new DicomServiceException(Status.ProcessingFailure, e);
            }
            accept(as.getCallingAET(), calledAet, temp, cuid, iuid, tsuid, null);
        }
    }

    /**
     * Instance reçue par STOW-RS (DICOMweb) : même traitement qu'une instance reçue par C-STORE (contrôles, routage,
     * rattachement à l'étude), à partir d'un fichier DICOM Part 10.
     *
     * @param source    émetteur, pour les journaux et le suivi (par exemple {@code dicomweb:pacs})
     * @param calledAet destination DICOM désignée par l'URL (route fixe ou destination de routage)
     * @param studyUid  étude imposée par l'URL, ou {@code null}
     * @return l'instance acceptée
     * @throws Refused si l'instance est refusée ; son statut DICOM donne la cause
     */
    public Accepted storePart10(String source, String calledAet, InputStream part10, String studyUid)
            throws Refused {
        // Verrou partagé : l'arrêt attend la fin de ce dépôt. Pris pendant l'arrêt, ou entrée non démarrée : refus.
        if (!stowGate.readLock().tryLock()) {
            throw notAccepting();
        }
        try {
            if (!accepting) {
                throw notAccepting();
            }
            return storeStarted(source, calledAet, part10, studyUid);
        } finally {
            stowGate.readLock().unlock();
        }
    }

    private static Refused notAccepting() {
        return new Refused(Status.OutOfResources, "Entrée DICOM non démarrée (démarrage ou arrêt de la box) : "
                + "réessayer dans quelques instants", null, null);
    }

    private Accepted storeStarted(String source, String calledAet, InputStream part10, String studyUid)
            throws Refused {
        if (calledAet == null || !config.isKnownCalledAet(calledAet)) {
            throw new Refused(Status.ProcessingFailure, "Destination inconnue : " + calledAet, null, null);
        }
        try {
            requireFreeSpace();
        } catch (IOException e) {
            throw new Refused(Status.OutOfResources, e.getMessage(), null, null);
        }
        Path temp = store.newTempFile();
        Attributes fmi;
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                part10.transferTo(new LimitedOutput(out, limits.maxInstanceSize()));
            }
            try (DicomInputStream in = new DicomInputStream(temp.toFile())) {
                fmi = in.readFileMetaInformation();
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            if (e instanceof DicomServiceException refused) {
                throw new Refused(refused.getStatus(), refused.getMessage(), null, null); // espace disque insuffisant
            }
            throw new Refused(Status.CannotUnderstand, "Fichier DICOM illisible : " + e.getMessage(), null, null);
        }
        if (fmi == null) {
            deleteQuietly(temp);
            throw new Refused(Status.CannotUnderstand, "Pas un fichier DICOM Part 10 (méta-informations)", null,
                    null);
        }
        String cuid = fmi.getString(Tag.MediaStorageSOPClassUID);
        String iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID);
        String tsuid = fmi.getString(Tag.TransferSyntaxUID);
        if (!DicomUids.isValid(cuid) || !DicomUids.isValid(iuid) || !DicomUids.isValid(tsuid)) {
            deleteQuietly(temp);
            throw new Refused(Status.CannotUnderstand, "UID des méta-informations invalide", null, null);
        }
        try {
            return accept(source, calledAet.trim(), temp, cuid, iuid, tsuid, studyUid);
        } catch (DicomServiceException e) {
            throw new Refused(e.getStatus(), e.getMessage(), cuid, iuid);
        }
    }

    /** Instance refusée par STOW-RS, avec ses UID s'ils ont pu être lus. */
    public static final class Refused extends DicomServiceException {

        private final String sopClassUid;
        private final String sopInstanceUid;

        Refused(int status, String message, String sopClassUid, String sopInstanceUid) {
            super(status, message);
            this.sopClassUid = sopClassUid;
            this.sopInstanceUid = sopInstanceUid;
        }

        public String sopClassUid() {
            return sopClassUid;
        }

        public String sopInstanceUid() {
            return sopInstanceUid;
        }
    }

    /** Failure Reason STOW-RS : instance d'une autre étude que celle imposée par l'URL. */
    static final int STUDY_MISMATCH = 0xA900;

    /**
     * Instance acceptée.
     *
     * @param sopClassUid    classe SOP
     * @param sopInstanceUid instance
     * @param studyUid       étude
     * @param destinations   partenaires destinataires
     */
    public record Accepted(String sopClassUid, String sopInstanceUid, String studyUid, List<String> destinations) {
    }

    /** Espace disque utilisable du dossier de réception. */
    @FunctionalInterface
    interface SpaceProbe {
        long usable() throws IOException;
    }

    /** Mesure de l'espace disque ; {@code null} : celle du système de fichiers du dossier de réception. */
    private volatile SpaceProbe space;
    /** Octets écrits entre deux contrôles de l'espace disque pendant une réception. */
    private volatile long spaceCheckInterval = 64L * 1024 * 1024;

    /** Remplace la mesure de l'espace disque et l'intervalle de contrôle (tests). */
    void space(SpaceProbe probe, long checkInterval) {
        this.space = java.util.Objects.requireNonNull(probe);
        this.spaceCheckInterval = checkInterval;
    }

    /**
     * Flux de sortie qui refuse plus de {@code limit} octets, et qui contrôle l'espace disque tous les
     * {@link #spaceCheckInterval} octets : une instance démesurée, ou plusieurs grosses reçues en même temps, ne
     * remplissent pas le disque (la base de la box, les envois et les réceptions y sont aussi).
     */
    private final class LimitedOutput extends java.io.FilterOutputStream {
        private final long limit;
        private long written;
        private long nextCheck = spaceCheckInterval;

        LimitedOutput(OutputStream out, long limit) {
            super(out);
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            written += length;
            if (written > limit) {
                throw new IOException("Instance trop grande (plus de " + limit + " octets)");
            }
            if (written >= nextCheck) {
                requireFreeSpace();
                nextCheck = written + spaceCheckInterval;
            }
            out.write(bytes, offset, length);
        }
    }

    /** AET venu du réseau, rendu sûr pour les journaux (pas de retour à la ligne ni de caractère de contrôle). */
    static String printable(String aet) {
        if (aet == null) {
            return null;
        }
        String cleaned = aet.replaceAll("[^\\x20-\\x7E]", "?");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) + "…" : cleaned;
    }

    private void requireFreeSpace() throws IOException {
        SpaceProbe probe = space;
        long usable = probe == null ? Files.getFileStore(store.root()).getUsableSpace() : probe.usable();
        if (usable < config.minFreeSpace()) {
            LOG.log(Level.ERROR, "Espace disque insuffisant : instance refusée");
            throw new DicomServiceException(Status.OutOfResources, "Espace disque insuffisant");
        }
    }

    /** Contrôle une instance écrite dans {@code temp} (Part 10), la range pour chaque partenaire et la rattache. */
    private Accepted accept(String source, String calledAet, Path temp, String cuid, String iuid, String tsuid,
                            String expectedStudy) throws DicomServiceException {
        try {
            Attributes dataset;
            try (DicomInputStream in = new DicomInputStream(temp.toFile())) {
                in.setIncludeBulkData(IncludeBulkData.NO);
                dataset = in.readDatasetUntilPixelData();
            }
            String studyUid = dataset.getString(Tag.StudyInstanceUID);
            String seriesUid = dataset.getString(Tag.SeriesInstanceUID);
            if (!DicomUids.isValid(studyUid) || !DicomUids.isValid(seriesUid)) {
                throw new DicomServiceException(Status.CannotUnderstand, "Study ou Series Instance UID invalide");
            }
            if (expectedStudy != null && !expectedStudy.equals(studyUid)) {
                throw new DicomServiceException(STUDY_MISMATCH, "Instance d'une autre étude que celle de l'URL");
            }
            if (!iuid.equals(dataset.getString(Tag.SOPInstanceUID))) {
                throw new DicomServiceException(Status.CannotUnderstand,
                        "SOP Instance UID du jeu de données différent de celui de la commande");
            }
            // Contrôlé ici comme à la restitution : accepté à la source, il ferait échouer l'étude entière chez le
            // destinataire, alors que le PACS d'origine aurait déjà oublié l'instance.
            if (!cuid.equals(dataset.getString(Tag.SOPClassUID))) {
                throw new DicomServiceException(Status.CannotUnderstand,
                        "SOP Class UID du jeu de données différent de celui de la commande");
            }
            List<String> destinations = destinations(calledAet, dataset).stream().distinct().toList();
            List<ReceivedInstance> placed = place(calledAet, destinations, temp, studyUid, seriesUid, cuid, iuid,
                    tsuid);
            // Toutes les copies sont en place : l'instance est rattachée à l'étude de chaque partenaire. Avant, un échec
            // de copie aurait laissé partir l'instance vers les premiers partenaires, refusée pourtant au PACS.
            for (int i = 0; i < destinations.size(); i++) {
                assembler.add(calledAet, destinations.get(i), source, studyUid, placed.get(i));
            }
            LOG.log(Level.DEBUG, "Instance reçue de {0} pour {1} → {2}", source, calledAet, destinations);
            return new Accepted(cuid, iuid, studyUid, destinations);
        } catch (DicomServiceException e) {
            deleteQuietly(temp);
            LOG.log(Level.WARNING, "Instance refusée (émetteur {0}) : {1}", source, e.getMessage());
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteQuietly(temp);
            LOG.log(Level.ERROR, "Échec de la réception d'une instance", e);
            throw new DicomServiceException(Status.ProcessingFailure, e);
        }
    }

    /**
     * Range l'instance pour chaque partenaire (déplacement vers le premier, copie vers les suivants). En cas d'échec,
     * les copies créées par cette réception sont retirées : rien n'est rattaché, le PACS reçoit un échec et renverra
     * l'instance. Un fichier qui existait déjà (même instance reçue plus tôt, déjà rattachée) est laissé en place.
     */
    private List<ReceivedInstance> place(String calledAet, List<String> destinations, Path temp, String studyUid,
                                         String seriesUid, String cuid, String iuid, String tsuid) throws IOException {
        List<Path> created = new java.util.ArrayList<>();
        List<ReceivedInstance> placed = new java.util.ArrayList<>();
        try {
            Path first = store.finalPath(calledAet, destinations.getFirst(), studyUid, seriesUid, iuid);
            for (String destination : destinations) {
                Path target = store.finalPath(calledAet, destination, studyUid, seriesUid, iuid);
                boolean existed = Files.exists(target);
                if (target.equals(first)) {
                    store.commit(temp, first);
                } else {
                    store.copy(first, target);
                }
                if (!existed) {
                    created.add(target);
                }
                placed.add(new ReceivedInstance(cuid, iuid, seriesUid, tsuid, target, Files.size(target)));
            }
            return placed;
        } catch (IOException | RuntimeException e) {
            created.forEach(DicomStoreScp::deleteQuietly);
            throw e;
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Fichier temporaire non supprimé : " + file, e);
        }
    }

    /**
     * Partenaires d'une instance : celui de la route fixe, ou ceux de la première règle qui correspond.
     *
     * @throws DicomServiceException si aucune règle ne correspond : l'instance est refusée, le PACS la garde en échec
     */
    private List<String> destinations(String calledAet, Attributes dataset) throws DicomServiceException {
        Optional<String> fixed = config.destinationFor(calledAet);
        if (fixed.isPresent()) {
            return List.of(fixed.get());
        }
        List<RoutingRule> rules = centralRouting.get().orElse(config.rules());
        RoutingRule rule = rules.stream().filter(r -> r.matches(dataset)).findFirst().orElseThrow(() ->
                new DicomServiceException(Status.ProcessingFailure,
                        "Aucune règle de routage ne correspond à cette instance"));
        LOG.log(Level.DEBUG, "Règle de routage {0} appliquée", rule.name());
        return rule.destinations();
    }

    /** Délai d'inactivité configuré (pour les journaux et la console). */
    public Duration studyIdleTimeout() {
        return config.studyIdleTimeout();
    }
}
