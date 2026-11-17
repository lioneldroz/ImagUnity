package com.imagunity.directory;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Demande d'enrôlement en attente dans l'annuaire, examinée sur le poste du responsable : signature de la box,
 * preuve et état du code d'enrôlement. L'annuaire n'étant pas de confiance, tout est revérifié ici avant de signer.
 *
 * @param boxId box à l'origine de la demande ({@code request.boxId()} si la demande est lisible)
 * @param request demande vérifiée ; {@code null} si {@link Status#ILLISIBLE}
 * @param code code d'enrôlement de ce responsable désigné par la demande ; {@code null} s'il n'y en a pas
 * @param detail explication à montrer au responsable
 */
record PendingRequest(UUID boxId, EnrollmentSubmission submission, EnrollmentRequest request, Status status,
                      EnrollmentCodes.Entry code, String detail) {

    /** Suite à donner à la demande ; le nom sert à l'interface locale. */
    enum Status {
        /** Preuve d'un code valide de ce responsable : nom et groupes viennent du code. */
        CODE_VERIFIE("code-verifie"),
        /** Sans code de ce responsable : enrôlement possible après comparaison de l'empreinte avec l'installateur. */
        SANS_CODE("sans-code"),
        PREUVE_INVALIDE("preuve-invalide"),
        CODE_UTILISE("code-utilise"),
        CODE_EXPIRE("code-expire"),
        ILLISIBLE("illisible");

        final String label;

        Status(String label) {
            this.label = label;
        }
    }

    /** Entrée publiée de la box, vérifiée avec la clé de l'organisation ; vide si la box n'a pas d'entrée. */
    @FunctionalInterface
    interface PublishedEntries {
        /**
         * @throws java.io.IOException si l'annuaire ne répond pas
         * @throws DirectoryException  si l'entrée publiée n'est pas signée par cette organisation ou est altérée
         */
        Optional<BoxEntry> of(UUID boxId) throws java.io.IOException;
    }

    /**
     * Examine un élément de la liste {@code GET /directory/v1/enrollment-requests}.
     *
     * <p>Un code est à usage unique. Seule la box qui l'a utilisé peut encore s'en prévaloir (elle renvoie sa demande
     * tant qu'elle ne se voit pas active), et seulement avec les mêmes clés de signature et si elle n'est pas révoquée :
     * sinon, quiconque détient le code pourrait faire réenrôler, avec ses propres clés, une box révoquée ou volée.
     * L'entrée publiée n'est lue ({@code published}) que dans ce cas.
     *
     * @return vide si l'élément, illisible, ne désigne même pas une box
     */
    static Optional<PendingRequest> assess(JsonNode item, EnrollmentCodes codes, Instant now,
                                           PublishedEntries published) throws java.io.IOException {
        UUID listed;
        try {
            listed = UUID.fromString(item.path("boxId").asString());
        } catch (IllegalArgumentException e) {
            listed = null;
        }
        EnrollmentSubmission submission;
        EnrollmentRequest request;
        try {
            submission = EnrollmentSubmission.fromJson(item.path("submission").toString());
            request = EnrollmentRequest.verify(submission.request());
        } catch (DirectoryException | IllegalArgumentException e) {
            return listed == null ? Optional.empty()
                    : Optional.of(new PendingRequest(listed, null, null, Status.ILLISIBLE, null, e.getMessage()));
        }
        Optional<EnrollmentCodes.Entry> found = codes.byId(submission.code());
        if (found.isEmpty()) {
            return Optional.of(new PendingRequest(request.boxId(), submission, request, Status.SANS_CODE, null,
                    "sans code d'enrôlement de ce responsable : à traiter à la main, après comparaison de "
                            + "l'empreinte avec l'installateur"));
        }
        EnrollmentCodes.Entry code = found.get();
        Status status;
        String detail;
        if (!submission.provenBy(code.code())) {
            status = Status.PREUVE_INVALIDE;
            detail = "preuve du code invalide : demande refusée";
        } else if (code.usedAt() != null && !request.boxId().equals(code.boxId())) {
            status = Status.CODE_UTILISE;
            detail = "code déjà utilisé par la box " + code.boxId();
        } else if (code.usedAt() != null && !keyFingerprint(request).equals(code.boxKey())) {
            status = Status.CODE_UTILISE;
            detail = code.boxKey() == null
                    ? "code déjà utilisé par cette box, avant que ses clés soient retenues : pour la réenrôler, "
                            + "tirer un nouveau code (code-creer) et le donner à l'installateur"
                    : "code déjà utilisé par cette box avec d'autres clés : refuser, sauf réinstallation confirmée "
                            + "par l'établissement (tirer alors un nouveau code)";
        } else if (code.usedAt() != null && revokedOrUnverifiable(request.boxId(), published)) {
            status = Status.CODE_UTILISE;
            detail = "code déjà utilisé par cette box, révoquée depuis (ou dont l'entrée publiée n'est pas "
                    + "vérifiable) : un code ne réintègre pas une box révoquée (nouvelle box, nouveau code)";
        } else if (!now.isBefore(code.expiresAt())) {
            status = Status.CODE_EXPIRE;
            detail = "code expiré le " + code.expiresAt().atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    + " : en tirer un autre (code-creer)";
        } else {
            status = Status.CODE_VERIFIE;
            detail = "code d'enrôlement vérifié";
        }
        return Optional.of(new PendingRequest(request.boxId(), submission, request, status, code, detail));
    }

    private static boolean revokedOrUnverifiable(UUID boxId, PublishedEntries published) throws java.io.IOException {
        try {
            return published.of(boxId).map(BoxEntry::revoked).orElse(false);
        } catch (DirectoryException | IllegalArgumentException e) {
            return true; // entrée altérée ou d'un autre responsable : la réutilisation n'est pas prouvée sûre
        }
    }

    /** Empreinte complète de la clé de signature de la demande (retenue dans le fichier des codes). */
    static String keyFingerprint(EnrollmentRequest request) {
        return HybridPublicKey.decode(request.signingKeys()).fingerprint();
    }

    /** Empreinte complète de la clé de signature de la box ; {@code null} si la demande est illisible. */
    String keyFingerprint() {
        return request == null ? null : keyFingerprint(request);
    }

    /** Vrai si le responsable peut signer l'entrée de cette box. */
    boolean signable() {
        return status == Status.CODE_VERIFIE || status == Status.SANS_CODE;
    }

    /** Empreinte de la clé de signature de la box, à comparer avec celle que lit l'installateur ; {@code null} si illisible. */
    String fingerprint() {
        return request == null ? null : HybridPublicKey.decode(request.signingKeys()).shortCode();
    }

    /** « nom proposé » (box, empreinte), pour les messages. */
    String label() {
        return request == null ? "Box " + boxId : "« " + request.displayName() + " » (" + boxId + ", empreinte "
                + fingerprint() + ")";
    }
}
