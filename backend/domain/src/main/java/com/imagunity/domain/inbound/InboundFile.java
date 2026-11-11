package com.imagunity.domain.inbound;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/**
 * Fichier proposé par un partenaire et téléchargé par la box réceptrice.
 *
 * @param transportFileId identifiant du fichier dans le transport
 * @param peerName        partenaire émetteur (nom local)
 * @param fileName        nom du fichier annoncé par l'émetteur ({@code <groupe>-<i>-of-<n>.tar.zst})
 * @param size            taille annoncée, en octets
 * @param state           avancement
 * @param file            emplacement local, une fois le téléchargement demandé
 * @param updatedAt       dernier changement
 */
public record InboundFile(long transportFileId, String peerName, String fileName, long size, State state, Path file,
                          Instant updatedAt) {

    public enum State {
        /** Téléchargement demandé au transport. */
        DOWNLOADING,
        /** Fichier entièrement reçu, pas encore vérifié. */
        DOWNLOADED,
        /** Étude restituée : fichier supprimé. */
        CONSUMED,
        /** Téléchargement impossible. */
        FAILED
    }

    public InboundFile {
        Objects.requireNonNull(peerName, "peerName");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public InboundFile with(State newState, Instant now) {
        return new InboundFile(transportFileId, peerName, fileName, size, newState, file, now);
    }

    public InboundFile downloaded(Path path, Instant now) {
        return new InboundFile(transportFileId, peerName, fileName, size, State.DOWNLOADED, path, now);
    }
}
