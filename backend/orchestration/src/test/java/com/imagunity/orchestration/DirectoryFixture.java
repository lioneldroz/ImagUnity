package com.imagunity.orchestration;

import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.EntryCache;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Organisation, box enrôlées et vue locale de l'annuaire pour les tests. */
final class DirectoryFixture {

    static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");
    final Organization organization = new Organization("chu-exemple", HybridKeyPair.generate());
    final List<SignedDocument> published = new ArrayList<>();

    BoxIdentity box() {
        return new BoxIdentity(UUID.randomUUID(), HybridKeyPair.generate());
    }

    SignedDocument enroll(BoxIdentity box, Instant from, Duration validity) {
        return enroll(box, from, validity, 1, true);
    }

    /** Entrée de numéro {@code serial}, avec ou sans les clés de chiffrement de la box. */
    SignedDocument enroll(BoxIdentity box, Instant from, Duration validity, long serial, boolean withEncryption) {
        SignedDocument entry = organization.enroll(EnrollmentRequest.create(box.keys(),
                withEncryption ? box.encryption().publicKey() : null, box.boxId(),
                "Box " + box.boxId().toString().substring(0, 8), Map.of(), from), from, validity, serial);
        published.add(entry);
        return entry;
    }

    SignedDocument enroll(BoxIdentity box) {
        return enroll(box, NOW, Duration.ofDays(30));
    }

    LocalDirectory directory(Clock clock) {
        EntryCache noCache = new EntryCache() {
            @Override
            public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
            }

            @Override
            public List<SignedDocument> entries() {
                return List.of();
            }
        };
        LocalDirectory directory = new LocalDirectory(new EntryVerifier("chu-exemple", organization.publicKey(),
                SerialMemory.inMemory(), clock), noCache, clock);
        directory.synchronize(() -> List.copyOf(published));
        return directory;
    }
}
