package com.imagunity.packaging;

import java.io.IOException;

/** Paquet invalide, incomplet ou altéré : il ne doit pas être restitué. */
public class PackageIntegrityException extends IOException {

    public PackageIntegrityException(String message) {
        super(message);
    }

    public PackageIntegrityException(String message, Throwable cause) {
        super(message, cause);
    }
}
