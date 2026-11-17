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
        /** Box révoquée dans l'annuaire : ni un code ni une comparaison d'empreinte ne la réintègrent. */
        BOX_REVOQUEE("box-revoquee"),
        /** Identifiant déjà publié avec d'autres clés (ou entrée invérifiable) : ce serait le prendre à cette box. */
        IDENTIFIANT_PRIS("identifiant-pris"),
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
     * <p>L'entrée déjà publiée pour cet identifiant ({@code published}) est toujours lue : une box révoquée n'est pas
     * réenrôlée, et un identifiant publié avec d'autres clés n'est pas repris, avec ou sans code. Un code est à usage
     * unique : seule la box qui l'a utilisé, avec les mêmes clés, peut encore s'en prévaloir (elle renvoie sa demande
     * tant qu'elle ne se voit pas active).
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
        // Identité de la box d'abord, avec ou sans code : signer une entrée pour un identifiant déjà publié avec
        // d'autres clés, ou pour une box révoquée, la donnerait au demandeur (numéro suivant, non révoquée), avec les
        // appairages et les règles qui la désignent.
        Optional<PendingRequest> identity = identityRefusal(submission, request, found.orElse(null), published);
        if (identity.isPresent()) {
            return identity;
        }
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

    /** Refus tenant à l'entrée déjà publiée pour cet identifiant de box ; vide si la box peut être (ré)enrôlée. */
    private static Optional<PendingRequest> identityRefusal(EnrollmentSubmission submission, EnrollmentRequest request,
                                                            EnrollmentCodes.Entry code, PublishedEntries published)
            throws java.io.IOException {
        Optional<BoxEntry> current;
        try {
            current = published.of(request.boxId());
        } catch (DirectoryException | IllegalArgumentException e) {
            return Optional.of(new PendingRequest(request.boxId(), submission, request, Status.IDENTIFIANT_PRIS, code,
                    "entrée publiée de cette box pas vérifiable (signée par un autre responsable, ou altérée) : "
                            + "refuser"));
        }
        if (current.isEmpty()) {
            return Optional.empty();
        }
        if (current.get().revoked()) {
            return Optional.of(new PendingRequest(request.boxId(), submission, request, Status.BOX_REVOQUEE, code,
                    "box révoquée dans l'annuaire : elle n'est pas réenrôlée (pour réintégrer l'établissement, "
                            + "nouvelle box et nouveau code)"));
        }
        if (!current.get().signingKey().fingerprint().equals(keyFingerprint(request))) {
            return Optional.of(new PendingRequest(request.boxId(), submission, request, Status.IDENTIFIANT_PRIS, code,
                    "identifiant déjà publié dans l'annuaire avec d'autres clés : refuser (une box réinstallée "
                            + "reçoit une nouvelle identité)"));
        }
        return Optional.empty();
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
