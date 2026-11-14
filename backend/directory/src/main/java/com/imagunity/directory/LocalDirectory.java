package com.imagunity.directory;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * Vue locale de l'annuaire, côté box et relais : entrées vérifiées avec la clé épinglée du responsable, gardées en
 * cache.
 *
 * <p>Une entrée qui ne se vérifie pas (signature, organisation, retour en arrière) est écartée et signalée ; une box
 * absente d'une nouvelle lecture garde sa dernière entrée connue (un annuaire compromis peut retenir des entrées,
 * pas les faire disparaître).
 *
 * <p><b>Fédération</b> (après {@link #federate}) : l'annuaire retient aussi les reconnaissances de partenaires signées
 * par le responsable, et les entrées de ces partenaires, vérifiées avec la clé que fixe la reconnaissance. Une entrée
 * de partenaire n'est utilisable ({@link #entry}) que si l'accord est actif, si ses groupes sont dans le périmètre et
 * si ses adresses sont sur les relais autorisés. Un identifiant de box n'appartient qu'à une organisation : l'entrée
 * locale l'emporte toujours, sinon la première organisation vue ; une revendication concurrente est écartée et
 * signalée ({@link #collisions}). Thread-safe.
 */
public final class LocalDirectory {

    private static final System.Logger LOG = System.getLogger(LocalDirectory.class.getName());
    /** Revendications d'identifiant gardées pour l'affichage. */
    static final int MAX_COLLISIONS = 100;
    /** Organisation propriétaire d'un identifiant tenu par une entrée locale, dans {@link Collision}. */
    public static final String LOCAL = "local";

    /**
     * État de la dernière synchronisation.
     *
     * @param at       date de la tentative ({@code null} : aucune)
     * @param accepted entrées retenues lors de cette tentative
     * @param rejected entrées écartées (mal signées, retour en arrière…)
     * @param error    cause de l'échec de lecture ({@code null} : lecture réussie)
     */
    public record SyncStatus(Instant at, int accepted, int rejected, String error) {
    }

    /**
     * Identifiant de box revendiqué par une organisation alors qu'une autre le tient.
     *
     * @param claimant empreinte de l'organisation dont l'entrée a été écartée (ou retirée)
     * @param holder   empreinte de l'organisation qui garde l'identifiant, ou {@link #LOCAL}
     */
    public record Collision(UUID boxId, String claimant, String holder, Instant at) {
    }

    private record Foreign(BoxEntry entry, String partner) {
    }

    private final EntryVerifier verifier;
    private final EntryCache cache;
    private final Clock clock;
    private final Map<UUID, BoxEntry> entries = new ConcurrentHashMap<>();
    private final Map<UUID, Foreign> foreign = new ConcurrentHashMap<>();
    private final Map<String, Recognition> recognitions = new ConcurrentHashMap<>();
    private final Map<String, EntryVerifier> partnerVerifiers = new ConcurrentHashMap<>();
    private final Map<String, SyncStatus> partnerSyncs = new ConcurrentHashMap<>();
    private final List<Collision> collisions = new CopyOnWriteArrayList<>();
    private volatile SyncStatus lastSync = new SyncStatus(null, 0, 0, null);
    private volatile SyncStatus recognitionSync = new SyncStatus(null, 0, 0, null);
    private volatile RecognitionVerifier recognitionVerifier;
    private volatile SerialMemory serials;

    public LocalDirectory(EntryVerifier verifier, EntryCache cache, Clock clock) {
        this.verifier = verifier;
        this.cache = cache;
        this.clock = clock;
    }

    /**
     * Active la fédération : reconnaissances vérifiées avec la clé du responsable, entrées des partenaires vérifiées
     * avec leur clé, numéros retenus dans {@code serials} (séparément pour chaque partenaire). À appeler avant
     * {@link #load}.
     */
    public LocalDirectory federate(RecognitionVerifier recognitionVerifier, SerialMemory serials) {
        this.recognitionVerifier = recognitionVerifier;
        this.serials = serials;
        return this;
    }

    public boolean federated() {
        return recognitionVerifier != null;
    }

    /** Recharge les entrées et reconnaissances du cache, revérifiées. */
    public synchronized void load() {
        List<SignedDocument> others = new ArrayList<>();
        for (SignedDocument document : cache.entries()) {
            if (RecognitionVerifier.isRecognition(document)) {
                if (federated()) {
                    try {
                        Recognition r = recognitionVerifier.verify(document);
                        recognitions.put(r.partner().fingerprint(), r);
                    } catch (DirectoryException e) {
                        LOG.log(Level.WARNING, "Reconnaissance en cache écartée : {0}", e.getMessage());
                    }
                }
                continue;
            }
            try {
                BoxEntry entry = verifier.verify(document).entry();
                entries.put(entry.boxId(), entry);
            } catch (DirectoryException e) {
                others.add(document);
            }
        }
        for (SignedDocument document : others) {
            Optional<Recognition> signer = recognitions.values().stream()
                    .filter(r -> document.isSignedBy(r.partner().publicKey())).findFirst();
            if (signer.isEmpty()) {
                LOG.log(Level.WARNING, "Entrée en cache écartée : signée par une organisation non reconnue");
                continue;
            }
            try {
                acceptForeign(signer.get(), document, false);
            } catch (DirectoryException e) {
                LOG.log(Level.WARNING, "Entrée de partenaire en cache écartée : {0}", e.getMessage());
            }
        }
    }

    /** Lit l'annuaire et retient les entrées qui se vérifient ; une lecture impossible est seulement notée. */
    public synchronized SyncStatus synchronize(Supplier<List<SignedDocument>> fetch) {
        Instant now = clock.instant();
        List<SignedDocument> documents;
        try {
            documents = fetch.get();
        } catch (DirectoryException e) {
            LOG.log(Level.WARNING, "Synchronisation de l''annuaire impossible : {0}", e.getMessage());
            lastSync = new SyncStatus(now, 0, 0, e.getMessage());
            return lastSync;
        }
        int accepted = 0;
        int rejected = 0;
        for (SignedDocument document : documents) {
            try {
                BoxEntry entry = verifier.verify(document).entry();
                cache.save(entry.boxId(), entry.serial(), document, now);
                entries.put(entry.boxId(), entry);
                Foreign displaced = foreign.remove(entry.boxId());
                if (displaced != null) {
                    collision(entry.boxId(), displaced.partner(), LOCAL);
                }
                accepted++;
            } catch (DirectoryException e) {
                rejected++;
                LOG.log(Level.WARNING, "Entrée de l''annuaire écartée : {0}", e.getMessage());
            }
        }
        lastSync = new SyncStatus(now, accepted, rejected, null);
        return lastSync;
    }

    /**
     * Lit les reconnaissances de partenaires publiées par le responsable et retient celles qui se vérifient.
     *
     * @throws IllegalStateException si la fédération n'est pas activée
     */
    public synchronized SyncStatus synchronizeRecognitions(Supplier<List<SignedDocument>> fetch) {
        requireFederated();
        Instant now = clock.instant();
        List<SignedDocument> documents;
        try {
            documents = fetch.get();
        } catch (DirectoryException e) {
            LOG.log(Level.WARNING, "Lecture des reconnaissances impossible : {0}", e.getMessage());
            recognitionSync = new SyncStatus(now, 0, 0, e.getMessage());
            return recognitionSync;
        }
        int accepted = 0;
        int rejected = 0;
        for (SignedDocument document : documents) {
            try {
                Recognition r = recognitionVerifier.verify(document);
                String partner = r.partner().fingerprint();
                cache.save(RecognitionVerifier.key(partner), r.serial(), document, now);
                Recognition before = recognitions.put(partner, r);
                if (before != null && before.serial() != r.serial()) {
                    partnerVerifiers.remove(partner);
                }
                accepted++;
            } catch (DirectoryException e) {
                rejected++;
                LOG.log(Level.WARNING, "Reconnaissance écartée : {0}", e.getMessage());
            }
        }
        recognitionSync = new SyncStatus(now, accepted, rejected, null);
        return recognitionSync;
    }

    /**
     * Lit les entrées d'un partenaire reconnu (relayées par l'annuaire local) et retient celles qui se vérifient avec
     * sa clé et ne revendiquent pas l'identifiant d'une box d'une autre organisation.
     *
     * @param partner empreinte de la clé du partenaire
     * @throws IllegalStateException si la fédération n'est pas activée
     */
    public synchronized SyncStatus synchronizePartner(String partner, Supplier<List<SignedDocument>> fetch) {
        requireFederated();
        Instant now = clock.instant();
        Recognition recognition = recognitions.get(partner);
        if (recognition == null) {
            SyncStatus status = new SyncStatus(now, 0, 0, "Organisation non reconnue");
            partnerSyncs.put(partner, status);
            return status;
        }
        List<SignedDocument> documents;
        try {
            documents = fetch.get();
        } catch (DirectoryException e) {
            LOG.log(Level.WARNING, "Lecture des entrées de « {0} » impossible : {1}",
                    recognition.partner().organization(), e.getMessage());
            SyncStatus status = new SyncStatus(now, 0, 0, e.getMessage());
            partnerSyncs.put(partner, status);
            return status;
        }
        int accepted = 0;
        int rejected = 0;
        for (SignedDocument document : documents) {
            try {
                acceptForeign(recognition, document, true);
                accepted++;
            } catch (DirectoryException e) {
                rejected++;
                LOG.log(Level.WARNING, "Entrée de « {0} » écartée : {1}", recognition.partner().organization(),
                        e.getMessage());
            }
        }
        SyncStatus status = new SyncStatus(now, accepted, rejected, null);
        partnerSyncs.put(partner, status);
        return status;
    }

    private void acceptForeign(Recognition recognition, SignedDocument document, boolean save) {
        String partner = recognition.partner().fingerprint();
        BoxEntry entry = partnerVerifier(recognition).verify(document).entry();
        UUID boxId = entry.boxId();
        if (entries.containsKey(boxId)) {
            collision(boxId, partner, LOCAL);
            throw new DirectoryException("Identifiant de box " + boxId + " déjà tenu par une box de l'organisation");
        }
        Foreign held = foreign.get(boxId);
        if (held != null && !held.partner().equals(partner)) {
            collision(boxId, partner, held.partner());
            throw new DirectoryException("Identifiant de box " + boxId + " déjà tenu par une autre organisation");
        }
        if (save) {
            cache.save(boxId, entry.serial(), document, clock.instant());
        }
        foreign.put(boxId, new Foreign(entry, partner));
    }

    private EntryVerifier partnerVerifier(Recognition recognition) {
        Recognition.Partner p = recognition.partner();
        return partnerVerifiers.computeIfAbsent(p.fingerprint(), fp -> new EntryVerifier(p.organization(),
                p.publicKey(), serials.scoped(fp), clock));
    }

    private void collision(UUID boxId, String claimant, String holder) {
        LOG.log(Level.WARNING, "Identifiant de box {0} revendiqué par deux organisations : {1} écartée, tenu par {2}",
                boxId, claimant, holder);
        collisions.add(new Collision(boxId, claimant, holder, clock.instant()));
        while (collisions.size() > MAX_COLLISIONS) {
            collisions.removeFirst();
        }
    }

    private void requireFederated() {
        if (!federated()) {
            throw new IllegalStateException("Fédération non activée");
        }
    }

    /**
     * Dernière entrée vérifiée et utilisable de cette box : locale, ou d'un partenaire dont l'accord est actif, dans
     * le périmètre et sur les relais autorisés.
     */
    public Optional<BoxEntry> entry(UUID boxId) {
        BoxEntry local = entries.get(boxId);
        if (local != null) {
            return Optional.of(local);
        }
        Foreign f = foreign.get(boxId);
        return f != null && usable(f) ? Optional.of(f.entry()) : Optional.empty();
    }

    private boolean usable(Foreign f) {
        Recognition r = recognitions.get(f.partner());
        return r != null && r.statusAt(clock.instant()) == BoxEntry.Status.ACTIVE
                && r.scope().allowsPartner(f.entry().groups()) && r.relaysAllowed(f.entry());
    }

    /** Entrées utilisables : celles de l'organisation, puis celles des partenaires (voir {@link #entry}). */
    public Collection<BoxEntry> entries() {
        List<BoxEntry> all = new ArrayList<>(entries.values());
        foreign.values().stream().filter(this::usable).map(Foreign::entry).forEach(all::add);
        return List.copyOf(all);
    }

    /** État d'une box maintenant ; {@code null} si elle est inconnue de l'annuaire (ou hors des accords). */
    public BoxEntry.Status status(UUID boxId) {
        return entry(boxId).map(e -> e.statusAt(clock.instant())).orElse(null);
    }

    /** Vrai si cette box est de l'organisation (et non d'un partenaire). */
    public boolean isLocal(UUID boxId) {
        return entries.containsKey(boxId);
    }

    /** Reconnaissance du partenaire auquel appartient cette box, si elle en est une (accord actif ou non). */
    public Optional<Recognition> partnerOf(UUID boxId) {
        Foreign f = foreign.get(boxId);
        return f == null ? Optional.empty() : Optional.ofNullable(recognitions.get(f.partner()));
    }

    /**
     * Vrai si cette box locale peut échanger avec cette autre box : toujours entre box de l'organisation (leur état
     * est contrôlé à part) ; avec une box de partenaire, si son entrée est utilisable et si la box locale est dans les
     * groupes locaux de l'accord.
     */
    public boolean exchangeAllowed(UUID localBox, UUID other) {
        if (isLocal(other)) {
            return true;
        }
        Optional<BoxEntry> remote = entry(other);
        BoxEntry local = entries.get(localBox);
        if (remote.isEmpty() || local == null) {
            return false;
        }
        return recognitions.get(foreign.get(other).partner()).scope().allowsLocal(local.groups());
    }

    /** Motif pour lequel l'échange avec cette box de partenaire est impossible ; vide s'il est possible. */
    public Optional<String> exchangeRefusal(UUID localBox, UUID other) {
        if (exchangeAllowed(localBox, other)) {
            return Optional.empty();
        }
        Foreign f = foreign.get(other);
        if (f == null) {
            return Optional.of("Box " + other + " absente de l'annuaire");
        }
        Recognition r = recognitions.get(f.partner());
        if (r == null) {
            return Optional.of("Box " + other + " d'une organisation non reconnue");
        }
        String name = "« " + r.partner().organization() + " »";
        BoxEntry.Status status = r.statusAt(clock.instant());
        if (status != BoxEntry.Status.ACTIVE) {
            return Optional.of("Accord avec " + name + (status == BoxEntry.Status.REVOKED ? " révoqué"
                    : status == BoxEntry.Status.EXPIRED ? " expiré" : " pas encore valide"));
        }
        if (!r.relaysAllowed(f.entry())) {
            return Optional.of("Box « " + f.entry().displayName() + " » sur un relais non autorisé par l'accord avec "
                    + name);
        }
        if (!r.scope().allowsPartner(f.entry().groups())) {
            return Optional.of("Box « " + f.entry().displayName() + " » hors du périmètre de l'accord avec " + name);
        }
        return Optional.of("Cette box n'est pas dans les groupes autorisés à échanger avec " + name);
    }

    /** Reconnaissances vérifiées, tous états confondus. */
    public List<Recognition> recognitions() {
        return List.copyOf(recognitions.values());
    }

    /** Reconnaissances actives maintenant : partenaires dont il faut lire les entrées. */
    public List<Recognition> activeRecognitions() {
        Instant now = clock.instant();
        return recognitions.values().stream().filter(r -> r.statusAt(now) == BoxEntry.Status.ACTIVE).toList();
    }

    /** Entrées en cache d'un partenaire, utilisables ou non (affichage). */
    public List<BoxEntry> partnerEntries(String partner) {
        return foreign.values().stream().filter(f -> f.partner().equals(partner)).map(Foreign::entry).toList();
    }

    /** Dernières revendications d'identifiant écartées, de la plus ancienne à la plus récente. */
    public List<Collision> collisions() {
        return List.copyOf(collisions);
    }

    public SyncStatus lastSync() {
        return lastSync;
    }

    public SyncStatus recognitionSync() {
        return recognitionSync;
    }

    /** Dernière lecture des entrées de ce partenaire ; {@code null} si aucune. */
    public SyncStatus partnerSync(String partner) {
        return partnerSyncs.get(partner);
    }
}
