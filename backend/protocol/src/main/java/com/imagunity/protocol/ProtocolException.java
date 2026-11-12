package com.imagunity.protocol;

/** Pièce du protocole refusée : mal formée, mal signée, altérée, ou hors des limites. */
public class ProtocolException extends RuntimeException {

    public ProtocolException(String message) {
        super(message);
    }

    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
