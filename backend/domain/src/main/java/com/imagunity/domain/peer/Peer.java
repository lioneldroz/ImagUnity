package com.imagunity.domain.peer;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Partenaire appairé : une box distante, connue localement sous un nom choisi à l'appairage.
 *
 * <p>Le nom est celui qu'utilisent les routes DICOM ({@code destination}). Il est choisi par
 * l'opérateur local, pas annoncé par le partenaire : un partenaire ne peut pas se faire passer
 * pour un autre en changeant son nom de profil.
 *
 * @param name            nom local du partenaire
 * @param transportPeerId identifiant du partenaire dans le transport
 * @param displayName     nom de profil annoncé par le partenaire (information seulement)
 * @param pairedAt        date de l'appairage
 */
public record Peer(String name, long transportPeerId, String displayName, Instant pairedAt) {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    public Peer {
        requireValidName(name);
        Objects.requireNonNull(pairedAt, "pairedAt");
    }

    /** @throws IllegalArgumentException si le nom n'est pas un nom de partenaire admis */
    public static String requireValidName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Nom de partenaire invalide (1 à 64 caractères parmi A-Z, a-z, 0-9, _ et -)");
        }
        return name;
    }
}
