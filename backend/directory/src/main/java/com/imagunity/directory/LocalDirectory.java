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
 * signalée ({@link #collisions}).
 *
 * <p><b>Fraîcheur</b> : la preuve de fraîcheur d'une organisation (la sienne ou celle d'un partenaire), signée par sa
 * clé de fraîcheur déléguée, annonce le plus haut numéro de chaque entrée et reconnaissance. Une box dont l'entrée
 * reçue est plus ancienne que l'annoncée n'est plus utilisable, le temps de recevoir la plus récente (une révocation
 * retenue ne passe pas). Une fois une preuve reçue d'une organisation, une preuve trop ancienne
 * ({@link #freshnessMaxAge}) arrête les échanges avec ses box (annuaire figé). Thread-safe.
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

    /** Fraîcheur par défaut au-delà de laquelle les échanges avec une organisation s'arrêtent. */
    public static final java.time.Duration DEFAULT_FRESHNESS_MAX_AGE = java.time.Duration.ofHours(48);

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
    private final Map<String, FreshnessProof> freshness = new ConcurrentHashMap<>();
    private final Map<String, SyncStatus> freshnessSyncs = new ConcurrentHashMap<>();
    private volatile java.time.Duration freshnessMaxAge = DEFAULT_FRESHNESS_MAX_AGE;
    private volatile String localOrganization;
    private volatile HybridPublicKey localKey;

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

    /**
     * Active le contrôle de fraîcheur de l'annuaire de l'organisation (la clé de son responsable) ; celui des
     * partenaires suit leurs reconnaissances. À appeler avant {@link #load}.
     *
     * @param maxAge âge maximal d'une preuve, une fois une preuve reçue ; zéro : jamais d'arrêt pour cause d'âge
     */
    public LocalDirectory checkFreshness(String organization, HybridPublicKey organizationKey,
                                         java.time.Duration maxAge) {
        this.localOrganization = organization;
        this.localKey = organizationKey;
        this.freshnessMaxAge = maxAge;
        return this;
    }

    /** Recharge les entrées et reconnaissances du cache, revérifiées. */
    public synchronized void load() {
        List<SignedDocument> others = new ArrayList<>();
        Map<String, SignedDocument> delegations = new java.util.HashMap<>();
        Map<String, SignedDocument> proofs = new java.util.HashMap<>();
        for (SignedDocument document : cache.entries()) {
            String type = RecognitionVerifier.type(document);
            if (FreshnessDelegation.TYPE.equals(type) || FreshnessProof.TYPE.equals(type)) {
                // Rangées par organisation : la délégation et sa preuve sont gardées sous des clés dérivées.
                (FreshnessDelegation.TYPE.equals(type) ? delegations : proofs).put(organizationOf(document), document);
                continue;
            }
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
        proofs.forEach((organization, proof) -> {
            SignedDocument delegation = delegations.get(organization);
            if (delegation != null) {
                try {
                    acceptFreshness(new Freshness(delegation, proof), false);
                } catch (DirectoryException e) {
                    LOG.log(Level.WARNING, "Preuve de fraîcheur en cache écartée : {0}", e.getMessage());
                }
            }
        });
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

    /** Identifiant d'organisation porté par un document signé, sans vérification. */
    private static String organizationOf(SignedDocument document) {
        try {
            return Json.MAPPER.readTree(document.payload()).path("organization").asString("");
        } catch (tools.jackson.core.JacksonException e) {
            return "";
        }
    }

    /** Clé d'organisation (empreinte) d'une preuve : celle de l'organisation, ou d'un partenaire reconnu. */
    private Optional<String[]> freshnessOwner(Freshness f) {
        FreshnessDelegation d;
        try {
            d = f.delegationContent();
        } catch (DirectoryException e) {
            return Optional.empty();
        }
        if (localKey != null && f.delegation().isSignedBy(localKey)) {
            return Optional.of(new String[] {localKey.fingerprint(), localOrganization});
        }
        return recognitions.values().stream().filter(r -> f.delegation().isSignedBy(r.partner().publicKey()))
                .findFirst().map(r -> new String[] {r.partner().fingerprint(), r.partner().organization()})
                .filter(o -> o[1].equals(d.organization()));
    }

    private FreshnessProof acceptFreshness(Freshness f, boolean save) {
        String[] owner = freshnessOwner(f).orElseThrow(() -> new DirectoryException(
                "Preuve de fraîcheur d'une organisation inconnue"));
        HybridPublicKey key = owner[0].equals(localKey == null ? null : localKey.fingerprint()) ? localKey
                : recognitions.get(owner[0]).partner().publicKey();
        FreshnessProof proof = f.verify(owner[1], key, clock.instant());
        FreshnessProof previous = freshness.get(owner[0]);
        if (previous != null && proof.issuedAt().isBefore(previous.issuedAt())) {
            throw new DirectoryException("Preuve de fraîcheur plus ancienne que la précédente : refusée");
        }
        freshness.put(owner[0], proof);
        if (save) {
            cache.save(java.util.UUID.nameUUIDFromBytes(("imagunity-delegation-fraicheur:" + owner[0])
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)), f.delegationContent().serial(), f.delegation(),
                    clock.instant());
            cache.save(java.util.UUID.nameUUIDFromBytes(("imagunity-fraicheur:" + owner[0])
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)), proof.issuedAt().getEpochSecond(), f.proof(),
                    clock.instant());
        }
        return proof;
    }

    /**
     * Lit la preuve de fraîcheur de l'annuaire de l'organisation ({@code null} : annuaire sans preuve de fraîcheur).
     *
     * @throws IllegalStateException si le contrôle de fraîcheur n'est pas activé
     */
    public synchronized SyncStatus synchronizeFreshness(Supplier<Optional<Freshness>> fetch) {
        if (localKey == null) {
            throw new IllegalStateException("Contrôle de fraîcheur non activé");
        }
        return synchronizeFreshness(localKey.fingerprint(), fetch);
    }

    /** Lit la preuve de fraîcheur d'un partenaire reconnu, relayée par l'annuaire de l'organisation. */
    public synchronized SyncStatus synchronizePartnerFreshness(String partner, Supplier<Optional<Freshness>> fetch) {
        if (!recognitions.containsKey(partner)) {
            SyncStatus status = new SyncStatus(clock.instant(), 0, 0, "Organisation non reconnue");
            freshnessSyncs.put(partner, status);
            return status;
        }
        return synchronizeFreshness(partner, fetch);
    }

    private SyncStatus synchronizeFreshness(String owner, Supplier<Optional<Freshness>> fetch) {
        Instant now = clock.instant();
        SyncStatus status;
        try {
            Optional<Freshness> f = fetch.get();
            if (f.isEmpty()) {
                status = new SyncStatus(now, 0, 0, "Annuaire sans preuve de fraîcheur");
            } else {
                String[] signer = freshnessOwner(f.get()).orElse(null);
                if (signer == null || !signer[0].equals(owner)) {
                    throw new DirectoryException("Preuve de fraîcheur d'une autre organisation");
                }
                acceptFreshness(f.get(), true);
                status = new SyncStatus(now, 1, 0, null);
            }
        } catch (DirectoryException e) {
            LOG.log(Level.WARNING, "Preuve de fraîcheur écartée : {0}", e.getMessage());
            status = new SyncStatus(now, 0, 1, e.getMessage());
        }
        freshnessSyncs.put(owner, status);
        return status;
    }

    /** Vrai si l'annuaire de cette organisation (empreinte de sa clé) est figé : preuve reçue, mais trop ancienne. */
    public boolean stale(String organizationFingerprint) {
        return stale(organizationFingerprint, "").isPresent();
    }

    /** Empreinte de la clé de l'organisation, si le contrôle de fraîcheur est activé. */
    public Optional<String> localFingerprint() {
        return Optional.ofNullable(localOwner());
    }

    /** Vrai si le contrôle de fraîcheur de l'annuaire de l'organisation est activé. */
    public boolean freshnessChecked() {
        return localKey != null;
    }

    /** Dernière preuve de fraîcheur retenue pour cette organisation (empreinte de sa clé). */
    public Optional<FreshnessProof> freshness(String organizationFingerprint) {
        return Optional.ofNullable(freshness.get(organizationFingerprint));
    }

    /** Dernière preuve de fraîcheur retenue pour l'annuaire de l'organisation. */
    public Optional<FreshnessProof> localFreshness() {
        return localKey == null ? Optional.empty() : freshness(localKey.fingerprint());
    }

    public SyncStatus freshnessSync(String organizationFingerprint) {
        return freshnessSyncs.get(organizationFingerprint);
    }

    /** Motif d'arrêt pour annuaire figé : preuve reçue un jour, mais plus ancienne que l'âge maximal. */
    private Optional<String> stale(String owner, String name) {
        FreshnessProof proof = owner == null ? null : freshness.get(owner);
        if (proof == null || freshnessMaxAge.isZero() || freshnessMaxAge.isNegative()) {
            return Optional.empty();
        }
        if (proof.issuedAt().plus(freshnessMaxAge).isBefore(clock.instant())) {
            return Optional.of("Annuaire " + name + " sans preuve de fraîcheur récente (dernière : " + proof.issuedAt()
                    + ") : échanges arrêtés jusqu'à sa prochaine lecture");
        }
        return Optional.empty();
    }

    /** Vrai si la preuve de fraîcheur annonce pour cette box une entrée plus récente que celle reçue. */
    private boolean lagging(String owner, BoxEntry entry) {
        FreshnessProof proof = owner == null ? null : freshness.get(owner);
        Long announced = proof == null ? null : proof.entries().get(entry.boxId());
        return announced != null && announced > entry.serial();
    }

    private boolean recognitionLagging(Recognition r) {
        FreshnessProof proof = localKey == null ? null : freshness.get(localKey.fingerprint());
        Long announced = proof == null ? null : proof.recognitions().get(r.partner().fingerprint());
        return announced != null && announced > r.serial();
    }

    private String localOwner() {
        return localKey == null ? null : localKey.fingerprint();
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
            return lagging(localOwner(), local) ? Optional.empty() : Optional.of(local);
        }
        Foreign f = foreign.get(boxId);
        return f != null && usable(f) ? Optional.of(f.entry()) : Optional.empty();
    }

    private boolean usable(Foreign f) {
        Recognition r = recognitions.get(f.partner());
        return r != null && r.statusAt(clock.instant()) == BoxEntry.Status.ACTIVE && !recognitionLagging(r)
                && r.scope().allowsPartner(f.entry().groups()) && r.relaysAllowed(f.entry())
                && !lagging(f.partner(), f.entry());
    }

    /** Entrées utilisables : celles de l'organisation, puis celles des partenaires (voir {@link #entry}). */
    public Collection<BoxEntry> entries() {
        List<BoxEntry> all = new ArrayList<>(entries.values().stream().filter(e -> !lagging(localOwner(), e)).toList());
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
     * Vrai si cette box locale peut échanger avec cette autre box : voir {@link #exchangeRefusal}.
     */
    public boolean exchangeAllowed(UUID localBox, UUID other) {
        return exchangeRefusal(localBox, other).isEmpty();
    }

    /**
     * Motif pour lequel l'échange avec cette box est impossible ; vide s'il est possible. Entre box de l'organisation
     * (leur état est contrôlé à part) : seulement une entrée en retard sur la preuve de fraîcheur, ou un annuaire figé.
     * Avec une box de partenaire : en plus, accord actif, périmètre des deux côtés, relais autorisés.
     */
    public Optional<String> exchangeRefusal(UUID localBox, UUID other) {
        BoxEntry local = entries.get(other);
        if (local != null) {
            if (lagging(localOwner(), local)) {
                return Optional.of("Entrée plus récente de la box « " + local.displayName() + " » annoncée par "
                        + "l'annuaire, pas encore reçue");
            }
            return stale(localOwner(), "de l'organisation");
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
        if (recognitionLagging(r)) {
            return Optional.of("Version plus récente de l'accord avec " + name + " annoncée par l'annuaire, pas encore "
                    + "reçue");
        }
        if (lagging(f.partner(), f.entry())) {
            return Optional.of("Entrée plus récente de la box « " + f.entry().displayName() + " » annoncée par "
                    + "l'annuaire de " + name + ", pas encore reçue");
        }
        if (!r.relaysAllowed(f.entry())) {
            return Optional.of("Box « " + f.entry().displayName() + " » sur un relais non autorisé par l'accord avec "
                    + name);
        }
        if (!r.scope().allowsPartner(f.entry().groups())) {
            return Optional.of("Box « " + f.entry().displayName() + " » hors du périmètre de l'accord avec " + name);
        }
        BoxEntry mine = entries.get(localBox);
        if (mine == null || !r.scope().allowsLocal(mine.groups())) {
            return Optional.of("Cette box n'est pas dans les groupes autorisés à échanger avec " + name);
        }
        return stale(f.partner(), "de " + name);
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
