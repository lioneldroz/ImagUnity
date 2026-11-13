package com.imagunity.orchestration;

import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportException;
import com.imagunity.transport.TransportPort;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Faux transport : enregistre les appels, simule des pannes, diffuse des événements. */
final class FakeTransport implements TransportPort {

    record SentFile(PeerId peer, Path file, long fileId) {
    }

    record SentMessage(PeerId peer, String text) {
    }

    final List<SentFile> files = new ArrayList<>();
    final List<SentMessage> messages = new ArrayList<>();
    final List<String> acceptedLinks = new ArrayList<>();
    final List<Consumer<TransportEvent>> listeners = new CopyOnWriteArrayList<>();
    boolean failSendFile;
    boolean failSendMessage;
    boolean failAccept;
    long nextFileId = 100;
    /** Box authentifiée par le transport pour chaque contact (vide : non fournie). */
    final java.util.Map<Long, java.util.UUID> authenticated = new java.util.HashMap<>();

    @Override
    public java.util.Optional<java.util.UUID> authenticatedBox(PeerId peer) {
        return java.util.Optional.ofNullable(authenticated.get(peer.value()));
    }

    @Override
    public String contactAddress() {
        return "https://relais.example/iu/v1/queues/" + "a".repeat(43);
    }

    @Override
    public void connect(String contactAddress) {
        if (failAccept) {
            throw new TransportException("adresse inconnue");
        }
        acceptedLinks.add(contactAddress);
    }

    @Override
    public void sendMessage(PeerId peer, String text) {
        if (failSendMessage) {
            throw new TransportException("relais injoignable");
        }
        messages.add(new SentMessage(peer, text));
    }

    @Override
    public long sendFile(PeerId peer, Path file) {
        if (failSendFile) {
            throw new TransportException("relais injoignable");
        }
        long id = nextFileId++;
        files.add(new SentFile(peer, file, id));
        return id;
    }

    /** Fichiers retirés du relais. */
    final List<Long> discarded = new ArrayList<>();
    boolean failDiscard;

    @Override
    public void discardSentFile(long fileId) {
        if (failDiscard) {
            throw new TransportException("fichier déjà supprimé");
        }
        discarded.add(fileId);
    }

    /** Téléchargements demandés : identifiant de fichier → emplacement cible. */
    final java.util.Map<Long, Path> downloads = new java.util.LinkedHashMap<>();
    boolean failReceive;

    @Override
    public void receiveFile(long fileId, Path target) {
        if (failReceive) {
            throw new TransportException("relais injoignable");
        }
        downloads.put(fileId, target);
    }

    @Override
    public void addListener(Consumer<TransportEvent> listener) {
        listeners.add(listener);
    }

    void emit(TransportEvent event) {
        listeners.forEach(l -> l.accept(event));
    }

    @Override
    public void close() {
    }
}
