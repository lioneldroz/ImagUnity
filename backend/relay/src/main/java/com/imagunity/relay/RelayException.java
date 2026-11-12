package com.imagunity.relay;

import org.springframework.http.HttpStatus;

/** Requête refusée par le relais, avec son code HTTP. */
public class RelayException extends RuntimeException {

    private final HttpStatus status;

    public RelayException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
