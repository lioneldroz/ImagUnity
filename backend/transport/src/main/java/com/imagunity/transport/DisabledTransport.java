package com.imagunity.transport;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Transport désactivé par configuration (développement, tests) : toute opération échoue avec un
 * message explicite, et aucun événement n'est jamais émis. La box démarre et reçoit les examens,
 * mais leurs transferts passent en échec.
 */
public final class DisabledTransport implements TransportPort {

    static final String MESSAGE = "Transport désactivé (imagunity.transport.simplex.enabled=false)";

    @Override
    public Invitation createInvitation() {
        throw new TransportException(MESSAGE);
    }

    @Override
    public void acceptInvitation(String invitationLink) {
        throw new TransportException(MESSAGE);
    }

    @Override
    public void sendMessage(PeerId peer, String text) {
        throw new TransportException(MESSAGE);
    }

    @Override
    public long sendFile(PeerId peer, Path file) {
        throw new TransportException(MESSAGE);
    }

    @Override
    public void receiveFile(long fileId, Path target) {
        throw new TransportException(MESSAGE);
    }

    @Override
    public void addListener(Consumer<TransportEvent> listener) {
        // aucun événement
    }

    @Override
    public void close() {
        // rien à fermer
    }
}
