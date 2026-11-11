package com.imagunity.transport;

import java.nio.file.Path;

/** Événements remontés par le transport. */
public sealed interface TransportEvent {

    /** Un partenaire est connecté : on peut lui envoyer messages et fichiers. */
    record PeerConnected(PeerId peer, String displayName) implements TransportEvent {
    }

    /** Message de signalisation reçu d'un partenaire. */
    record MessageReceived(PeerId peer, String text) implements TransportEvent {
    }

    /** Un partenaire annonce un fichier ; il faut l'accepter avec {@link TransportPort#receiveFile}. */
    record FileOffered(PeerId peer, long fileId, String fileName, long size) implements TransportEvent {
    }

    /** Fichier entièrement téléchargé. */
    record FileReceived(PeerId peer, long fileId, Path path) implements TransportEvent {
    }

    /** Fichier entièrement déposé sur les relais. */
    record FileSent(PeerId peer, long fileId) implements TransportEvent {
    }

    /** Échec d'envoi ou de réception d'un fichier. */
    record FileFailed(long fileId, String reason) implements TransportEvent {
    }
}
