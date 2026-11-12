package com.imagunity.transport;

import java.nio.file.Path;

/** Événements remontés par le transport. */
public sealed interface TransportEvent {

    /** Un partenaire est connecté : on peut lui envoyer messages et fichiers. */
    /**
     * Une box demande à se connecter par l'adresse de contact de cette box ; à accepter par
     * {@link TransportPort#acceptContactRequest} (ou à ignorer).
     *
     * @param requestId   identifiant de la demande dans le transport
     * @param displayName nom de profil annoncé par le demandeur (information seulement)
     */
    record ContactRequested(long requestId, String displayName) implements TransportEvent {
    }

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

    /** Sens d'un transfert de fichier. */
    enum Direction {
        /** Téléversement vers les relais. */
        SENDING,
        /** Téléchargement depuis les relais. */
        RECEIVING
    }

    /**
     * Avancement d'un transfert, pour l'affichage. Les tailles sont celles vues par le
     * transport (données chiffrées, morceaux complétés) : elles peuvent différer légèrement
     * de la taille du fichier. Seul leur rapport est significatif.
     */
    record FileProgress(long fileId, Direction direction, long transferred, long total) implements TransportEvent {

        /** Avancement entre 0 et 1, ou 0 si le total est inconnu. */
        public double ratio() {
            return total > 0 ? Math.min(1.0, (double) transferred / total) : 0.0;
        }
    }
}
