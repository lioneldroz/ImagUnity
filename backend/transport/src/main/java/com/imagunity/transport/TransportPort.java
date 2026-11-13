package com.imagunity.transport;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Transport entre box, indépendant de son implémentation.
 *
 * <p>Le cœur métier ne connaît que cette interface. Implémentation : {@code IuTransport}, qui parle directement au
 * relais IU-T de l'organisation (spécification {@code docs/reference/specification-protocole.md}) ; {@link DisabledTransport}
 * sans relais configuré.
 *
 * <p>Les méthodes sont bloquantes (prévues pour des threads virtuels). Les arrivées
 * (partenaire connecté, message, fichier) sont signalées par des {@link TransportEvent}.
 */
public interface TransportPort extends AutoCloseable {

    /**
     * Adresse de contact durable de cette box (créée au premier appel), publiée par sa demande d'enrôlement dans
     * l'annuaire.
     *
     * @throws TransportException si le transport ne propose pas d'adresse
     */
    String contactAddress();

    /**
     * Adresses de contact à publier dans la demande d'enrôlement (champ {@code transport} de l'entrée) : une par relais
     * pour un transport à plusieurs relais ({@code iu}, {@code iu-2}…).
     */
    default java.util.Map<String, String> contactAddresses() {
        return java.util.Map.of(addressKind(), contactAddress());
    }

    /** Clé de l'adresse de contact dans le champ {@code transport} des entrées d'annuaire. */
    default String addressKind() {
        return "iu";
    }

    /**
     * Appaire la box de l'annuaire qui publie cette adresse de contact. La connexion est confirmée par un événement
     * {@link TransportEvent.PeerConnected} ; la box distante crée le contact de son côté.
     */
    void connect(String contactAddress);

    /**
     * Box authentifiée par le transport pour ce contact (IU-T : la box dont les messages signés arrivent de ce
     * contact), si le transport en connaît une. La poignée de main et l'appairage exigent qu'elle corresponde à la box
     * annoncée : une box ne peut pas se faire enregistrer sous l'identité d'une autre.
     */
    default java.util.Optional<java.util.UUID> authenticatedBox(PeerId peer) {
        return java.util.Optional.empty();
    }

    /** Envoie un petit message de signalisation (quelques Ko au plus). */
    void sendMessage(PeerId peer, String text);

    /**
     * Dépose un fichier sur le relais et l'annonce au partenaire.
     *
     * @return l'identifiant local du fichier, repris dans les événements de suivi
     */
    long sendFile(PeerId peer, Path file);

    /**
     * Retire du relais un fichier envoyé dont le destinataire n'a plus besoin (après son accusé), au lieu
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
     * Rejoue les arrivées que l'orchestrateur a pu manquer pendant un arrêt de la box (fichiers proposés, reçus ou
     * envoyés). À appeler une fois les écouteurs abonnés. Les événements rejoués peuvent être des doublons :
     * les écouteurs doivent être idempotents.
     */
    default void catchUp() {
    }

    @Override
    void close();
}
