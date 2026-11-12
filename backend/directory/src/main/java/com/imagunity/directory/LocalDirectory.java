package com.imagunity.directory;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Vue locale de l'annuaire, côté box : entrées vérifiées avec la clé épinglée du responsable, gardées en cache.
 *
 * <p>Une entrée qui ne se vérifie pas (signature, organisation, retour en arrière) est écartée et signalée ; une box
 * absente d'une nouvelle lecture garde sa dernière entrée connue (un annuaire compromis peut retenir des entrées,
 * pas les faire disparaître). Thread-safe.
 */
public final class LocalDirectory {

    private static final System.Logger LOG = System.getLogger(LocalDirectory.class.getName());

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

    private final EntryVerifier verifier;
    private final EntryCache cache;
    private final Clock clock;
    private final Map<UUID, BoxEntry> entries = new ConcurrentHashMap<>();
    private volatile SyncStatus lastSync = new SyncStatus(null, 0, 0, null);

    public LocalDirectory(EntryVerifier verifier, EntryCache cache, Clock clock) {
        this.verifier = verifier;
        this.cache = cache;
        this.clock = clock;
    }

    /** Recharge les entrées du cache, revérifiées. */
    public void load() {
        for (SignedDocument document : cache.entries()) {
            try {
                BoxEntry entry = verifier.verify(document).entry();
                entries.put(entry.boxId(), entry);
            } catch (DirectoryException e) {
                LOG.log(Level.WARNING, "Entrée en cache écartée : {0}", e.getMessage());
            }
        }
    }

    /** Lit l'annuaire et retient les entrées qui se vérifient ; une lecture impossible est seulement notée. */
    public SyncStatus synchronize(Supplier<List<SignedDocument>> fetch) {
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
                accepted++;
            } catch (DirectoryException e) {
                rejected++;
                LOG.log(Level.WARNING, "Entrée de l''annuaire écartée : {0}", e.getMessage());
            }
        }
        lastSync = new SyncStatus(now, accepted, rejected, null);
        return lastSync;
    }

    /** Dernière entrée vérifiée de cette box. */
    public Optional<BoxEntry> entry(UUID boxId) {
        return Optional.ofNullable(entries.get(boxId));
    }

    public Collection<BoxEntry> entries() {
        return List.copyOf(entries.values());
    }

    /** État d'une box maintenant ; {@code null} si elle est inconnue de l'annuaire. */
    public BoxEntry.Status status(UUID boxId) {
        BoxEntry entry = entries.get(boxId);
        return entry == null ? null : entry.statusAt(clock.instant());
    }

    public SyncStatus lastSync() {
        return lastSync;
    }
}
