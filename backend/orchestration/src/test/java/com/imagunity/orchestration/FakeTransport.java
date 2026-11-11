package com.imagunity.orchestration;

import com.imagunity.transport.Invitation;
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

    @Override
    public Invitation createInvitation() {
        return new Invitation("https://simplex.chat/invitation#test", null);
    }

    @Override
    public void acceptInvitation(String invitationLink) {
        if (failAccept) {
            throw new TransportException("lien invalide");
        }
        acceptedLinks.add(invitationLink);
    }

    @Override
    public void sendMessage(PeerId peer, String text) {
        if (failSendMessage) {
            throw new TransportException("relais SMP injoignable");
        }
        messages.add(new SentMessage(peer, text));
    }

    @Override
    public long sendFile(PeerId peer, Path file) {
        if (failSendFile) {
            throw new TransportException("relais XFTP injoignable");
        }
        long id = nextFileId++;
        files.add(new SentFile(peer, file, id));
        return id;
    }

    @Override
    public void receiveFile(long fileId, Path target) {
        throw new UnsupportedOperationException();
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
