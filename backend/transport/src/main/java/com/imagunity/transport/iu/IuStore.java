package com.imagunity.transport.iu;

import com.imagunity.protocol.Codec;
import com.imagunity.transport.TransportException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * État durable du transport IU-T, dans un dossier de la box : file de réception, contacts, messages à déposer, envois
 * et réceptions de fichiers (avec l'avancement par morceau, pour reprendre après une coupure ou un redémarrage).
 *
 * <pre>
 * file.json              file de réception (identifiant de relève et clé : secrets)
 * contacts.json          compteur des identifiants locaux, et contacts (box de l'annuaire)
 * sortants/&lt;n&gt;.json      messages scellés, en attente de dépôt
 * envois/&lt;id&gt;.json       envois de fichiers (morceaux et leurs clés)
 * receptions/&lt;id&gt;.json   réceptions de fichiers (description, morceaux déjà écrits)
 * </pre>
 * Chaque fichier est écrit à côté puis mis en place d'un coup : une coupure ne laisse pas de fichier tronqué. Les
 * fichiers sont lisibles par la box seule.
 */
final class IuStore {

    /** Un contact : box de l'annuaire, et l'identifiant local qui la désigne auprès de l'orchestrateur. */
    record Contact(long peerId, UUID boxId) {
    }

    record Contacts(long next, List<Contact> contacts) {
        Contacts {
            contacts = List.copyOf(contacts);
        }
    }

    /** Message scellé en attente de dépôt dans la file de {@code to}. */
    record Pending(long seq, UUID to, String envelope, Instant createdAt) {
    }

    /** Envoi d'un fichier ; {@code state} vaut {@code null} tant que les morceaux ne sont pas créés. */
    record Outgoing(long fileId, long peerId, UUID boxId, String path, IuFileUploader.UploadState state,
                    boolean announced, Instant createdAt) {

        Outgoing with(IuFileUploader.UploadState updated) {
            return new Outgoing(fileId, peerId, boxId, path, updated, announced, createdAt);
        }

        Outgoing announcedNow() {
            return new Outgoing(fileId, peerId, boxId, path, state, true, createdAt);
        }
    }

    /** Étapes d'une réception. */
    enum Status {
        /** Description en cours de réception (en plusieurs messages). */
        COLLECTING,
        /** Proposée à l'orchestrateur ; en attente de son accord. */
        OFFERED,
        DOWNLOADING,
        RECEIVED,
        FAILED
    }

    /** Réception d'un fichier. */
    record Incoming(long fileId, long peerId, UUID boxId, String transferId, List<String> fragments,
                    String description, String target, List<Integer> done, Status status, Instant createdAt) {

        Incoming {
            fragments = fragments == null ? null : java.util.Collections.unmodifiableList(new ArrayList<>(fragments));
            done = List.copyOf(done);
        }

        Incoming with(Status next) {
            return new Incoming(fileId, peerId, boxId, transferId, fragments, description, target, done, next,
                    createdAt);
        }
    }

    /**
     * Premier identifiant local : au-dessus de ceux de SimpleX (petits entiers), pour qu'un partenaire ou un fichier
     * déjà en base d'avant la bascule ne soit jamais confondu avec un contact ou un fichier IU-T.
     */
    static final long FIRST_ID = 1_000_000;

    private final Path dir;

    IuStore(Path dir) {
        this.dir = dir;
        try {
            for (String sub : List.of("sortants", "envois", "receptions")) {
                Files.createDirectories(dir.resolve(sub));
            }
        } catch (IOException e) {
            throw new TransportException("Dossier d'état IU-T inutilisable : " + dir, e);
        }
    }

    // --- File de réception ---

    synchronized Optional<IuMailbox.Queue> queue() {
        return read(dir.resolve("file.json")).map(IuMailbox.Queue::fromJson);
    }

    synchronized void saveQueue(IuMailbox.Queue queue) {
        write(dir.resolve("file.json"), queue.toJson());
    }

    /** Identifiant de dépôt réservé par la box, publié dans sa demande d'enrôlement avant la création de sa file. */
    synchronized Optional<String> reservedSendId() {
        return read(dir.resolve("file-reservee.txt")).map(String::strip);
    }

    synchronized void saveReservedSendId(String sendId) {
        write(dir.resolve("file-reservee.txt"), sendId);
    }

    // --- Contacts et identifiants ---

    private Contacts contacts() {
        return read(dir.resolve("contacts.json")).map(j -> Codec.json().readValue(j, Contacts.class))
                .orElse(new Contacts(FIRST_ID, List.of()));
    }

    /** Identifiant local neuf (contacts et fichiers), jamais réattribué. */
    synchronized long nextId() {
        Contacts current = contacts();
        write(dir.resolve("contacts.json"), Codec.json().writeValueAsString(
                new Contacts(current.next() + 1, current.contacts())));
        return current.next();
    }

    synchronized Optional<Contact> contactOf(UUID boxId) {
        return contacts().contacts().stream().filter(c -> c.boxId().equals(boxId)).findFirst();
    }

    synchronized Optional<Contact> contact(long peerId) {
        return contacts().contacts().stream().filter(c -> c.peerId() == peerId).findFirst();
    }

    /** Contact de cette box, créé s'il n'existe pas ; {@code created} dit lequel. */
    synchronized Contact addContact(UUID boxId, boolean[] created) {
        Optional<Contact> existing = contactOf(boxId);
        if (existing.isPresent()) {
            created[0] = false;
            return existing.get();
        }
        Contacts current = contacts();
        Contact contact = new Contact(current.next(), boxId);
        List<Contact> all = new ArrayList<>(current.contacts());
        all.add(contact);
        write(dir.resolve("contacts.json"), Codec.json().writeValueAsString(new Contacts(current.next() + 1, all)));
        created[0] = true;
        return contact;
    }

    /** Vrai une fois les partenaires d'avant IU-T repris (marqueur {@code partenaires-repris}). */
    synchronized boolean peersMigrated() {
        return Files.exists(dir.resolve("partenaires-repris"));
    }

    synchronized void markPeersMigrated() {
        write(dir.resolve("partenaires-repris"), "");
    }

    // --- Messages à déposer ---

    synchronized void addPending(UUID to, byte[] envelope, Instant now) {
        long seq = nextId();
        write(dir.resolve("sortants").resolve(String.format("%019d.json", seq)), Codec.json().writeValueAsString(
                new Pending(seq, to, Codec.base64Url(envelope), now)));
    }

    synchronized List<Pending> pending() {
        return list("sortants", Pending.class).stream().sorted(Comparator.comparingLong(Pending::seq)).toList();
    }

    synchronized void removePending(long seq) {
        delete(dir.resolve("sortants").resolve(String.format("%019d.json", seq)));
    }

    // --- Envois ---

    synchronized void save(Outgoing outgoing) {
        write(dir.resolve("envois").resolve(outgoing.fileId() + ".json"), Codec.json().writeValueAsString(outgoing));
    }

    synchronized Optional<Outgoing> outgoing(long fileId) {
        return read(dir.resolve("envois").resolve(fileId + ".json"))
                .map(j -> Codec.json().readValue(j, Outgoing.class));
    }

    synchronized List<Outgoing> outgoing() {
        return list("envois", Outgoing.class);
    }

    synchronized void deleteOutgoing(long fileId) {
        delete(dir.resolve("envois").resolve(fileId + ".json"));
    }

    // --- Réceptions ---

    synchronized void save(Incoming incoming) {
        write(dir.resolve("receptions").resolve(incoming.fileId() + ".json"),
                Codec.json().writeValueAsString(incoming));
    }

    synchronized Optional<Incoming> incoming(long fileId) {
        return read(dir.resolve("receptions").resolve(fileId + ".json"))
                .map(j -> Codec.json().readValue(j, Incoming.class));
    }

    synchronized Optional<Incoming> incoming(UUID boxId, String transferId) {
        return incoming().stream().filter(i -> i.boxId().equals(boxId) && i.transferId().equals(transferId))
                .findFirst();
    }

    synchronized List<Incoming> incoming() {
        return list("receptions", Incoming.class);
    }

    synchronized void deleteIncoming(long fileId) {
        delete(dir.resolve("receptions").resolve(fileId + ".json"));
    }

    // --- Fichiers ---

    private <T> List<T> list(String sub, Class<T> type) {
        try (Stream<Path> files = Files.list(dir.resolve(sub))) {
            return files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted()
                    .map(p -> Codec.json().readValue(readString(p), type)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Optional<String> read(Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.of(readString(file));
    }

    private static String readString(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void write(Path file, String content) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            restrict(tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void restrict(Path file) throws IOException {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            // système de fichiers sans droits POSIX
        }
    }

    private static void delete(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
