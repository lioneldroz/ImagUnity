package com.imagunity.directory;

/**
 * Demande d'enrôlement envoyée par une box au service d'annuaire, en attente de la signature du responsable.
 *
 * @param request demande signée par la box (preuve de possession de ses clés)
 * @param code    identifiant du code d'enrôlement de la box ({@link EnrollmentCode#id}), ou {@code null} sans code
 * @param proof   preuve calculée avec ce code ({@link EnrollmentCode#proof}), ou {@code null} sans code
 */
public record EnrollmentSubmission(SignedDocument request, String code, String proof) {

    /** Plus longue demande transmise. */
    public static final int MAX_SIZE = 64 * 1024;

    public EnrollmentSubmission {
        java.util.Objects.requireNonNull(request, "request");
        if ((code == null) != (proof == null)) {
            throw new DirectoryException("Demande transmise : code et preuve vont ensemble");
        }
        if (code != null && (!EnrollmentCode.ID.matcher(code).matches()
                || !EnrollmentCode.PROOF.matcher(proof).matches())) {
            throw new DirectoryException("Demande transmise : code ou preuve mal formés");
        }
    }

    /** Demande de cette box, avec la preuve de son code d'enrôlement s'il y en a un. */
    public static EnrollmentSubmission of(SignedDocument request, String enrollmentCode) {
        if (enrollmentCode == null || enrollmentCode.isBlank()) {
            return new EnrollmentSubmission(request, null, null);
        }
        return new EnrollmentSubmission(request, EnrollmentCode.id(enrollmentCode),
                EnrollmentCode.proof(enrollmentCode, request));
    }

    /** Vrai si cette demande a été envoyée par la box qui détient ce code. */
    public boolean provenBy(String enrollmentCode) {
        return code != null && code.equals(EnrollmentCode.id(enrollmentCode))
                && EnrollmentCode.verify(enrollmentCode, request, proof);
    }

    public String toJson() {
        return Json.write(this);
    }

    /**
     * Lit une demande transmise ; ne vérifie pas la demande elle-même ({@link EnrollmentRequest#verify}).
     *
     * @throws DirectoryException si elle est illisible
     */
    public static EnrollmentSubmission fromJson(String json) {
        return Json.read(json, EnrollmentSubmission.class, "Demande transmise");
    }
}
