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

    public FederationService(RecognitionStore store, String organization, HybridPublicKey organizationKey,
                             Clock clock) {
        this(store, organization, organizationKey, clock,
                directory -> new DirectoryClient(directory, Duration.ofSeconds(20)).fetch());
    }

    /** @param partnerDirectory lecture des entrées publiées par l'annuaire d'un partenaire */
    FederationService(RecognitionStore store, String organization, HybridPublicKey organizationKey, Clock clock,
                      Function<URI, List<SignedDocument>> partnerDirectory) {
        this.store = store;
        this.organization = organization;
        this.organizationKey = organizationKey;
        this.clock = clock;
        this.partnerDirectory = partnerDirectory;
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

    private Optional<Recognition> active(String partner) {
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
        Recognition r = active(partner).orElseThrow(() -> new NoSuchElementException(
                "Aucun accord actif avec cette organisation"));
        return new PartnerEntries(r.partner().organization(), store.partnerEntries(partner));
    }

    /** Entrées relayées d'un partenaire. */
    public record PartnerEntries(String organization, List<SignedDocument> entries) {
    }

    /** Lit l'annuaire de chaque partenaire reconnu et relaie les entrées qui se vérifient avec sa clé. */
    public void refreshPartners() {
        for (Current c : currentRecognitions()) {
            Recognition r = c.recognition();
            if (r.statusAt(clock.instant()) != BoxEntry.Status.ACTIVE) {
                continue;
            }
            Recognition.Partner p = r.partner();
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
