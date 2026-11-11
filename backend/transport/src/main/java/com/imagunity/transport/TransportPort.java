package com.imagunity.transport;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Transport entre box, indépendant de son implémentation.
 *
 * <p>Le cœur métier ne connaît que cette interface. L'implémentation actuelle
 * ({@code SimplexTransport}) pilote un client simplex-chat exécuté comme processus
 * séparé ; une réimplémentation propriétaire de SMP et XFTP ne remplacerait que
 * l'adaptateur.
 *
 * <p>Les méthodes sont bloquantes (prévues pour des threads virtuels). Les arrivées
 * (partenaire connecté, message, fichier) sont signalées par des {@link TransportEvent}.
 *
 * <p><b>Statut :</b> brouillon de la phase 1, à stabiliser à l'issue des essais.
 */
public interface TransportPort extends AutoCloseable {

    /**
     * Crée un lien d'invitation à usage unique, à transmettre hors bande au partenaire
     * (QR code, code court). C'est l'appairage de la preuve de concept, sans annuaire.
     */
    Invitation createInvitation();

    /**
     * Rejoint le partenaire qui a émis ce lien. La connexion s'établit de façon
     * asynchrone : elle est confirmée par un événement {@link TransportEvent.PeerConnected}.
     */
    void acceptInvitation(String invitationLink);

    /** Envoie un petit message de signalisation (quelques Ko au plus). */
    void sendMessage(PeerId peer, String text);

    /**
     * Dépose un fichier sur les relais et l'annonce au partenaire.
     *
     * @return l'identifiant local du fichier, repris dans les événements de suivi
     */
    long sendFile(PeerId peer, Path file);

    /**
     * Retire des relais un fichier envoyé dont le destinataire n'a plus besoin (après son accusé), au lieu
     * d'attendre son expiration. Sans effet si le transport ne le permet pas.
     *
     * @param fileId identifiant renvoyé par {@link #sendFile}
     */
    default void discardSentFile(long fileId) {
    }

    /** Accepte un fichier annoncé et le télécharge à l'emplacement donné. */
    void receiveFile(long fileId, Path target);

    /** Abonne un écouteur à tous les événements du transport. */
    void addListener(Consumer<TransportEvent> listener);

    /**
     * Rejoue les arrivées récentes (messages, fichiers proposés ou reçus) que le transport a pu recevoir
     * pendant que la box n'était pas à l'écoute (box redémarrée alors que le client de transport tournait).
     * À appeler une fois les écouteurs abonnés. Les événements rejoués peuvent être des doublons :
     * les écouteurs doivent être idempotents.
     */
    default void catchUp() {
    }

    @Override
    void close();
}
