package com.imagunity.domain.exam;

import java.util.regex.Pattern;

/**
 * Contrôle des UID DICOM (PS3.5 § 9.1) : composantes numériques séparées par des points,
 * 64 caractères au plus.
 *
 * <p>Les UID servent de noms de fichiers et de dossiers (réception, paquets) : ce contrôle
 * protège aussi contre les chemins forgés (un UID valide ne peut contenir ni {@code /}, ni {@code ..}).
 */
public final class DicomUids {

    /** Expression d'un UID, réutilisable dans d'autres motifs (sans ancres). */
    public static final String REGEX = "(?:0|[1-9][0-9]*)(?:\\.(?:0|[1-9][0-9]*))*";

    private static final Pattern UID = Pattern.compile(REGEX);

    private DicomUids() {
    }

    public static boolean isValid(String uid) {
        return uid != null && uid.length() <= 64 && UID.matcher(uid).matches();
    }
}
