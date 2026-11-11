package com.imagunity.dicom;

import java.util.Objects;

/**
 * PACS local vers lequel la box restitue les études reçues.
 *
 * @param host       adresse du PACS (mini-PACS de l'établissement)
 * @param port       port DICOM du PACS
 * @param calledAet  AET du PACS
 * @param callingAet AET sous lequel la box se présente (à autoriser dans le PACS)
 */
public record RestoreConfig(String host, int port, String calledAet, String callingAet) {

    public RestoreConfig {
        Objects.requireNonNull(host, "host");
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port DICOM du PACS invalide : " + port);
        }
        requireAet(calledAet, "AET du PACS");
        requireAet(callingAet, "AET de la box");
    }

    private static void requireAet(String aet, String what) {
        if (aet == null || aet.isBlank() || aet.length() > 16) {
            throw new IllegalArgumentException(what + " invalide (1 à 16 caractères)");
        }
    }
}
