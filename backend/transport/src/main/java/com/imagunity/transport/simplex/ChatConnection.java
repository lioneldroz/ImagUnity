package com.imagunity.transport.simplex;

/**
 * Canal texte vers le client simplex-chat. Isole le WebSocket pour pouvoir tester
 * le reste de l'adaptateur sans processus simplex-chat.
 */
interface ChatConnection extends AutoCloseable {

    /** Envoie un message texte complet (une commande JSON). */
    void send(String text);

    @Override
    void close();
}
