package com.imagunity.dicom;

import java.util.regex.Pattern;

/**
 * Contrôle des UID DICOM (PS3.5 § 9.1) : composantes numériques séparées par des points,
 * 64 caractères au plus.
 *
 * <p>Les UID servent de noms de fichiers et de dossiers : ce contrôle protège aussi contre
 * les chemins forgés (un UID valide ne peut contenir ni {@code /}, ni {@code ..}).
 */
final class DicomUids {

    private static final Pattern UID = Pattern.compile("(0|[1-9][0-9]*)(\\.(0|[1-9][0-9]*))*");

    private DicomUids() {
    }

    static boolean isValid(String uid) {
        return uid != null && uid.length() <= 64 && UID.matcher(uid).matches();
    }
}
