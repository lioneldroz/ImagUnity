package com.imagunity.directoryserver;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.DirectoryClient;
import com.imagunity.directory.DirectoryException;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridPublicKey;
import com.imagunity.directory.Recognition;
import com.imagunity.directory.RecognitionVerifier;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fédération, côté service d'annuaire : publication des reconnaissances signées par le responsable, et relais des
 * entrées de chaque partenaire reconnu, lues sur son annuaire. Le service n'étant pas de confiance, box et relais
 * revérifient tout ; il vérifie quand même ce qu'il publie ou relaie, pour ne pas servir n'importe quoi.
 */
public class FederationService {

    private static final Logger LOG = LoggerFactory.getLogger(FederationService.class);

    /** Lecture d'un document de reconnaissance, avec son document signé. */
    record Current(Recognition recognition, SignedDocument document) {
    }

    private final RecognitionStore store;
    private final String organization;
    private final Clock clock;
    private final Function<URI, List<SignedDocument>> partnerDirectory;
    private final Function<URI, Optional<com.imagunity.directory.Freshness>> partnerFreshness;

    public FederationService(RecognitionStore store, String organization, HybridPublicKey organizationKey,
                             Clock clock) {
        this(store, organization, organizationKey, clock, (com.imagunity.directory.SignedRead) null);
    }

    /**
     * @param reader lectures des annuaires des partenaires signées par la clé de fraîcheur de cette organisation
     *               (lecture réservée chez eux) ; {@code null} sans clé de fraîcheur
     */
    public FederationService(RecognitionStore store, String organization, HybridPublicKey organizationKey,
                             Clock clock, com.imagunity.directory.SignedRead reader) {
        this(store, organization, organizationKey, clock, new PartnerClients(reader));
    }

    private FederationService(RecognitionStore store, String organization, HybridPublicKey organizationKey,
                              Clock clock, PartnerClients clients) {
        this(store, organization, organizationKey, clock, directory -> clients.of(directory).fetch(),
                directory -> clients.of(directory).fetchFreshness());
    }

    /** Un client par annuaire de partenaire, pour garder son curseur (lecture par différence). */
    private record PartnerClients(com.imagunity.directory.SignedRead reader,
                                  java.util.Map<URI, DirectoryClient> clients) {
        PartnerClients(com.imagunity.directory.SignedRead reader) {
            this(reader, new java.util.concurrent.ConcurrentHashMap<>());
        }

        DirectoryClient of(URI directory) {
            return clients.computeIfAbsent(directory, d -> {
                DirectoryClient client = new DirectoryClient(d, Duration.ofSeconds(20));
                return reader == null ? client : client.readAs(reader);
            });
        }
    }

    FederationService(RecognitionStore store, String organization, HybridPublicKey organizationKey, Clock clock,
                      Function<URI, List<SignedDocument>> partnerDirectory) {
        this(store, organization, organizationKey, clock, partnerDirectory, directory -> Optional.empty());
    }

    /**
     * @param partnerDirectory lecture des entrées publiées par l'annuaire d'un partenaire
     * @param partnerFreshness lecture de la preuve de fraîcheur publiée par l'annuaire d'un partenaire
     */
    FederationService(RecognitionStore store, String organization, HybridPublicKey organizationKey, Clock clock,
                      Function<URI, List<SignedDocument>> partnerDirectory,
                      Function<URI, Optional<com.imagunity.directory.Freshness>> partnerFreshness) {
        this.store = store;
        this.organization = organization;
        this.organizationKey = organizationKey;
        this.clock = clock;
        this.partnerDirectory = partnerDirectory;
        this.partnerFreshness = partnerFreshness;
    }

    private final HybridPublicKey organizationKey;

    String organization() {
        return organization;
    }

    /** Vérification sans mémoire des numéros : ils sont contrôlés contre la base. */
    private Recognition verify(SignedDocument document) {
        return new RecognitionVerifier(organization, organizationKey, SerialMemory.inMemory()).verify(document);
    }

    /** Dépôt d'une reconnaissance par le responsable. */
    @Transactional
    public PublicationService.Outcome publish(SignedDocument document) {
        Recognition recognition;
        try {
            recognition = verify(document);
        } catch (DirectoryException e) {
            throw new PublicationService.InvalidEntryException(e.getMessage());
        }
        String partner = recognition.partner().fingerprint();
        OptionalLong highest = store.highestSerial(partner);
        if (highest.isPresent() && recognition.serial() < highest.getAsLong()) {
            throw new PublicationService.ConflictException("Retour en arrière : la reconnaissance de « "
                    + recognition.partner().organization() + " » est déjà au n° " + highest.getAsLong());
        }
        if (highest.isPresent() && recognition.serial() == highest.getAsLong()) {
            if (store.find(partner, recognition.serial()).orElseThrow().equals(document)) {
                return PublicationService.Outcome.UNCHANGED;
            }
            throw new PublicationService.ConflictException("Une autre reconnaissance n° " + recognition.serial()
                    + " est déjà publiée pour « " + recognition.partner().organization() + " »");
        }
        try {
            store.save(recognition, document, clock.instant());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new PublicationService.ConflictException("Une autre reconnaissance n° " + recognition.serial()
                    + " vient d'être publiée pour « " + recognition.partner().organization() + " »");
        }
        LOG.info("Reconnaissance publiée : « {} » (empreinte {}), n° {}{}", recognition.partner().organization(),
                recognition.partner().shortCode(), recognition.serial(), recognition.revoked() ? " (révocation)" : "");
        return PublicationService.Outcome.PUBLISHED;
    }

    /** Reconnaissance courante de chaque partenaire. */
    public List<SignedDocument> current() {
        return store.current();
    }

    private List<Current> currentRecognitions() {
        return store.current().stream().map(d -> new Current(verify(d), d)).toList();
    }

    /** Reconnaissance active de ce partenaire, s'il y en a une. */
    public Optional<Recognition> activePartner(String partner) {
        return currentRecognitions().stream().map(Current::recognition)
                .filter(r -> r.partner().fingerprint().equals(partner)
                        && r.statusAt(clock.instant()) == BoxEntry.Status.ACTIVE)
                .findFirst();
    }

    /**
     * Entrées relayées d'un partenaire dont l'accord est actif.
     *
     * @throws NoSuchElementException si aucun accord actif ne le reconnaît
     */
    public PartnerEntries partnerEntries(String partner) {
        return partnerEntries(partner, 0);
    }

    /** Entrées relayées d'un partenaire dont l'accord est actif, reçues après le curseur {@code since}. */
    public PartnerEntries partnerEntries(String partner, long since) {
        Recognition r = activePartner(partner).orElseThrow(() -> new NoSuchElementException(
                "Aucun accord actif avec cette organisation"));
        long cursor = store.partnerCursor(partner);
        return new PartnerEntries(r.partner().organization(), store.partnerEntriesSince(partner, since), cursor);
    }

    /**
     * Clé de fraîcheur déléguée d'un partenaire dont l'accord est actif, d'après sa dernière preuve relayée : elle
     * signe les lectures de son service d'annuaire (lecture réservée).
     */
    public Optional<HybridPublicKey> partnerReadKey(String partner) {
        Optional<Recognition> r = activePartner(partner);
        if (r.isEmpty()) {
            return Optional.empty();
        }
        Recognition.Partner p = r.get().partner();
        return store.partnerFreshness(partner).flatMap(f -> {
            try {
                f.verify(p.organization(), p.publicKey(), clock.instant());
                com.imagunity.directory.FreshnessDelegation d = f.delegationContent();
                return d.coversAt(clock.instant()) ? Optional.of(d.publicKey()) : Optional.empty();
            } catch (DirectoryException e) {
                return Optional.empty();
            }
        });
    }

    /**
     * Preuve de fraîcheur relayée d'un partenaire dont l'accord est actif.
     *
     * @throws NoSuchElementException si aucun accord actif ne le reconnaît, ou si aucune preuve n'a été relayée
     */
    public com.imagunity.directory.Freshness partnerFreshness(String partner) {
        activePartner(partner).orElseThrow(() -> new NoSuchElementException("Aucun accord actif avec cette organisation"));
        return store.partnerFreshness(partner).orElseThrow(() -> new NoSuchElementException(
                "Aucune preuve de fraîcheur relayée pour cette organisation"));
    }

    /** Entrées relayées d'un partenaire. */
    public record PartnerEntries(String organization, List<SignedDocument> entries, long cursor) {
    }

    /** Lit l'annuaire de chaque partenaire reconnu et relaie les entrées qui se vérifient avec sa clé. */
    public void refreshPartners() {
        for (Current c : currentRecognitions()) {
            Recognition r = c.recognition();
            if (r.statusAt(clock.instant()) != BoxEntry.Status.ACTIVE) {
                continue;
            }
            Recognition.Partner p = r.partner();
            // Preuve de fraîcheur d'abord : plus ancienne que les entrées lues ensuite, elle n'en signale aucune à tort.
            try {
                partnerFreshness.apply(URI.create(p.directory())).ifPresent(f -> {
                    com.imagunity.directory.FreshnessProof proof = f.verify(p.organization(), p.publicKey(),
                            clock.instant());
                    store.savePartnerFreshness(p.fingerprint(), f, proof.issuedAt(), clock.instant());
                });
            } catch (DirectoryException e) {
                LOG.warn("Preuve de fraîcheur de « {} » écartée : {}", p.organization(), e.getMessage());
            }
            List<SignedDocument> documents;
            try {
                documents = partnerDirectory.apply(URI.create(p.directory()));
            } catch (DirectoryException e) {
                LOG.warn("Annuaire de « {} » illisible : {}", p.organization(), e.getMessage());
                continue;
            }
            EntryVerifier entries = new EntryVerifier(p.organization(), p.publicKey(), SerialMemory.inMemory(), clock);
            int relayed = 0;
            int rejected = 0;
            for (SignedDocument document : documents) {
                try {
                    BoxEntry entry = entries.verify(document).entry();
                    if (store.savePartnerEntry(p.fingerprint(), entry.boxId(), entry.serial(), document,
                            clock.instant())) {
                        relayed++;
                    }
                } catch (DirectoryException e) {
                    rejected++;
                }
            }
            if (relayed > 0 || rejected > 0) {
                LOG.info("Annuaire de « {} » : {} entrée(s) nouvelle(s) relayée(s), {} écartée(s)", p.organization(),
                        relayed, rejected);
            }
        }
    }
}
