package com.imagunity.dicom;

import com.imagunity.domain.exam.DicomUids;
import java.io.IOException;
import java.io.InputStream;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.dcm4che3.net.Status;

/**
 * Réception STOW-RS (DICOMweb, PS3.18 § 10.5) : dépôt d'instances par HTTP, en plus du C-STORE.
 *
 * <p>La requête porte des instances au format DICOM Part 10, en {@code multipart/related;
 * type="application/dicom"}. Chacune suit le même chemin qu'une instance reçue par C-STORE : contrôles, routage
 * (route fixe ou règles de la destination de routage), rattachement à son étude. La destination DICOM est
 * désignée par l'URL ({@code /dicomweb/<AET>/studies}).
 *
 * <p>Réponse : DICOM JSON avec les instances acceptées (Referenced SOP Sequence) et refusées (Failed SOP Sequence,
 * avec la cause) ; code HTTP 200 si tout est accepté, 202 si une partie seulement, 409 si rien.
 */
public final class StowService {

    private static final System.Logger LOG = System.getLogger(StowService.class.getName());
    static final String DICOM = "application/dicom";

    private final DicomStoreScp scp;

    public StowService(DicomStoreScp scp) {
        this.scp = scp;
    }

    /**
     * Réponse STOW-RS.
     *
     * @param status      code HTTP
     * @param contentType type de contenu
     * @param body        corps (DICOM JSON, ou message d'erreur)
     */
    public record Response(int status, String contentType, String body) {
    }

    /**
     * Traite une requête STOW-RS.
     *
     * @param source      émetteur (compte DICOMweb), pour les journaux et le suivi
     * @param calledAet   destination DICOM désignée par l'URL
     * @param studyUid    étude imposée par l'URL ({@code /studies/<UID>}), ou {@code null}
     * @param contentType type de contenu de la requête
     */
    public Response store(String source, String calledAet, String studyUid, String contentType, InputStream body)
            throws IOException {
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("multipart/related")) {
            return error(415, "Type de contenu attendu : multipart/related; type=\"application/dicom\"");
        }
        String type = MultipartRelatedReader.parameter(contentType, "type");
        if (type != null && !type.equalsIgnoreCase(DICOM)) {
            return error(415, "Seules les instances DICOM Part 10 (application/dicom) sont acceptées, pas " + type);
        }
        String boundary = MultipartRelatedReader.parameter(contentType, "boundary");
        if (boundary == null || boundary.isEmpty()) {
            return error(400, "Délimiteur multipart (boundary) absent");
        }
        if (studyUid != null && !DicomUids.isValid(studyUid)) {
            return error(400, "Study Instance UID de l'URL invalide");
        }
        MultipartRelatedReader reader;
        try {
            reader = new MultipartRelatedReader(body, boundary);
        } catch (IllegalArgumentException e) {
            return error(400, e.getMessage());
        }
        List<String> referenced = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try {
            while (reader.next()) {
                String partType = reader.headers().get("content-type");
                if (partType != null && !partType.toLowerCase(Locale.ROOT).startsWith(DICOM)) {
                    failed.add(item(null, null, Status.CannotUnderstand));
                    continue;
                }
                try {
                    DicomStoreScp.Accepted accepted = scp.storePart10(source, calledAet, reader.body(), studyUid);
                    referenced.add(item(accepted.sopClassUid(), accepted.sopInstanceUid(), null));
                } catch (DicomStoreScp.Refused e) {
                    failed.add(item(e.sopClassUid(), e.sopInstanceUid(), e.getStatus()));
                }
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Requête STOW-RS de {0} interrompue : {1}", source, e.getMessage());
            if (referenced.isEmpty() && failed.isEmpty()) {
                return error(400, "Corps multipart illisible : " + e.getMessage());
            }
            failed.add(item(null, null, Status.ProcessingFailure));
        }
        if (referenced.isEmpty() && failed.isEmpty()) {
            return error(400, "Aucune instance dans la requête");
        }
        LOG.log(Level.INFO, "STOW-RS de {0} vers {1} : {2} instance(s) acceptée(s), {3} refusée(s)", source,
                calledAet, referenced.size(), failed.size());
        int status = failed.isEmpty() ? 200 : referenced.isEmpty() ? 409 : 202;
        StringBuilder json = new StringBuilder("{");
        if (!failed.isEmpty()) {
            json.append("\"00081198\":{\"vr\":\"SQ\",\"Value\":[").append(String.join(",", failed)).append("]}");
        }
        if (!referenced.isEmpty()) {
            json.append(failed.isEmpty() ? "" : ",")
                    .append("\"00081199\":{\"vr\":\"SQ\",\"Value\":[").append(String.join(",", referenced))
                    .append("]}");
        }
        return new Response(status, "application/dicom+json", json.append('}').toString());
    }

    /** Élément de séquence : classe et instance SOP (si connues), cause d'échec (si refusée). */
    private static String item(String sopClassUid, String sopInstanceUid, Integer failureReason) {
        List<String> fields = new ArrayList<>();
        if (sopClassUid != null) {
            fields.add("\"00081150\":{\"vr\":\"UI\",\"Value\":[\"" + sopClassUid + "\"]}");
        }
        if (sopInstanceUid != null) {
            fields.add("\"00081155\":{\"vr\":\"UI\",\"Value\":[\"" + sopInstanceUid + "\"]}");
        }
        if (failureReason != null) {
            fields.add("\"00081197\":{\"vr\":\"US\",\"Value\":[" + failureReason + "]}");
        }
        return "{" + String.join(",", fields) + "}";
    }

    private static Response error(int status, String message) {
        return new Response(status, "text/plain;charset=UTF-8", message);
    }
}
