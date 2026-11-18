package com.imagunity.dicom;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.DimseRSP;
import org.dcm4che3.net.IncompatibleConnectionException;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;

/**
 * Essai de connexion DICOM (C-ECHO) vers un appareil du site, comme le ferait un « ping » DICOM : l'appareil
 * répond-il, à cette adresse, sous cet AET, et accepte-t-il la box sous le sien ? Aucune donnée d'examen n'est envoyée.
 */
public final class DicomEcho {

    /** Délai par défaut de connexion, d'acceptation et de réponse. */
    public static final Duration TIMEOUT = Duration.ofSeconds(5);

    private DicomEcho() {
    }

    /**
     * @return vide si l'appareil a répondu avec succès ; sinon la raison de l'échec, en clair
     */
    public static Optional<String> echo(String host, int port, String calledAet, String callingAet, Duration timeout) {
        int millis = Math.toIntExact(timeout.toMillis());
        Device device = new Device("imagunity-echo");
        Connection local = new Connection();
        local.setConnectTimeout(millis);
        local.setAcceptTimeout(millis);
        local.setResponseTimeout(millis);
        local.setReleaseTimeout(millis);
        ApplicationEntity ae = new ApplicationEntity(callingAet);
        device.addConnection(local);
        device.addApplicationEntity(ae);
        ae.addConnection(local);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("dicom-echo").factory());
        device.setExecutor(executor);
        device.setScheduledExecutor(scheduler);
        Connection remote = new Connection();
        remote.setHostname(host);
        remote.setPort(port);
        AAssociateRQ rq = new AAssociateRQ();
        rq.setCalledAET(calledAet);
        rq.addPresentationContext(new PresentationContext(1, UID.Verification, UID.ImplicitVRLittleEndian));
        try {
            Association as = ae.connect(remote, rq);
            try {
                DimseRSP rsp = as.cecho();
                rsp.next();
                int status = rsp.getCommand().getInt(Tag.Status, -1);
                return status == Status.Success ? Optional.empty()
                        : Optional.of(String.format("réponse C-ECHO en échec (statut %04X)", status));
            } finally {
                try {
                    as.release();
                } catch (IOException ignored) {
                    // l'essai a déjà sa réponse
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.of("essai interrompu");
        } catch (IncompatibleConnectionException | GeneralSecurityException | IOException | RuntimeException e) {
            return Optional.of(reason(e));
        } finally {
            executor.shutdownNow();
            scheduler.shutdownNow();
        }
    }

    private static String reason(Exception e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (e instanceof java.net.ConnectException || message.contains("Connection refused")) {
            return "connexion refusée (rien n'écoute à cette adresse et ce port)";
        }
        if (e instanceof java.net.SocketTimeoutException || message.contains("timed out")) {
            return "pas de réponse dans le délai (adresse injoignable ou pare-feu)";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "adresse introuvable : " + message;
        }
        if (e instanceof org.dcm4che3.net.pdu.AAssociateRJ) {
            return "association refusée par l'appareil (AET de la box ou de l'appareil non reconnu) : " + message;
        }
        return message.length() > 200 ? message.substring(0, 200) : message;
    }
}
