package com.imagunity.transport.iu;

import com.imagunity.transport.TransportException;

/** Requête refusée par le relais (code HTTP 4xx ou 5xx), avec son code et sa raison. */
public final class RelayRefusedException extends TransportException {

    private final int status;

    public RelayRefusedException(int status, String message) {
        super("Relais : HTTP " + status + " — " + message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    /** Vrai si un nouvel essai a des chances d'aboutir (relais surchargé, morceau pas encore prêt, panne). */
    public boolean retryable() {
        return status == 425 || status == 429 || status >= 500;
    }
}
