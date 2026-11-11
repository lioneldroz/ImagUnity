package com.imagunity.orchestration;

/** La relance demandée n'est pas possible (transfert pas en échec, paquets disparus…). */
public final class RetryRefusedException extends RuntimeException {

    public RetryRefusedException(String message) {
        super(message);
    }
}
