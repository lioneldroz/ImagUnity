package com.imagunity.release;

/** Manifeste, clé ou artefact refusé. */
public class ReleaseException extends RuntimeException {

    public ReleaseException(String message) {
        super(message);
    }

    public ReleaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
