package com.imagunity.relay;

import org.springframework.http.HttpStatus;

/** Requête refusée par le relais, avec son code HTTP. */
public class RelayException extends RuntimeException {

    private final HttpStatus status;
    private final long retryAfter;

    public RelayException(HttpStatus status, String message) {
        this(status, message, 0);
    }

    /** @param retryAfter secondes à attendre avant un nouvel essai (en-tête {@code Retry-After}) ; 0 : aucun */
    public RelayException(HttpStatus status, String message, long retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public long retryAfter() {
        return retryAfter;
    }

    public HttpStatus status() {
        return status;
    }
}
