package com.imagunity.scenarios;

import java.io.IOException;
import java.net.ServerSocket;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.net.Association;
import org.dcm4che3.net.ApplicationEntity;
import org.dcm4che3.net.Connection;
import org.dcm4che3.net.Device;
import org.dcm4che3.net.PDVInputStream;
import org.dcm4che3.net.Status;
import org.dcm4che3.net.TransferCapability;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.BasicCEchoSCP;
import org.dcm4che3.net.service.BasicCStoreSCP;
import org.dcm4che3.net.service.DicomServiceException;
import org.dcm4che3.net.service.DicomServiceRegistry;

/**
 * PACS simulé de l'établissement destinataire : reçoit en C-STORE les études que sa box restitue et garde chaque
 * instance en mémoire. Il peut refuser les instances ({@link #refuse}) ou être arrêté puis redémarré sur le même port
 * (PACS en panne).
 */
final class FakePacs implements AutoCloseable {

    final String aet;
    final int port;
    private final Map<String, Attributes> received = new ConcurrentHashMap<>();
    private volatile boolean refuse;
    private Device device;
    private ExecutorService executor;
    private ScheduledExecutorService scheduler;

    FakePacs(String aet) {
        this.aet = aet;
        this.port = freePort();
    }

    synchronized FakePacs start() {
        if (device != null) {
            return this;
        }
        Device d = new Device("pacs-simule-" + aet);
        Connection connection = new Connection();
        connection.setPort(port);
        connection.setBindAddress("127.0.0.1");
        ApplicationEntity ae = new ApplicationEntity("*");
        ae.setAssociationAcceptor(true);
        ae.addConnection(connection);
        ae.addTransferCapability(new TransferCapability(null, "*", TransferCapability.Role.SCP, "*"));
        d.addConnection(connection);
        d.addApplicationEntity(ae);
        DicomServiceRegistry services = new DicomServiceRegistry();
        services.addDicomService(new BasicCEchoSCP());
        services.addDicomService(new BasicCStoreSCP("*") {
            @Override
            protected void store(Association as, PresentationContext pc, Attributes rq, PDVInputStream data,
                                 Attributes rsp) throws IOException {
                Attributes dataset = data.readDataset(pc.getTransferSyntax());
                if (refuse) {
                    throw new DicomServiceException(Status.OutOfResources, "PACS simulé : refus demandé");
                }
                received.put(dataset.getString(Tag.SOPInstanceUID), dataset);
            }
        });
        d.setDimseRQHandler(services);
        executor = Executors.newCachedThreadPool();
        scheduler = Executors.newSingleThreadScheduledExecutor();
        d.setExecutor(executor);
        d.setScheduledExecutor(scheduler);
        try {
            d.bindConnections();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("PACS simulé : port " + port + " indisponible", e);
        }
        device = d;
        return this;
    }

    /** Arrête le PACS : la box ne peut plus le joindre. */
    synchronized void stop() {
        if (device == null) {
            return;
        }
        device.unbindConnections();
        executor.shutdownNow();
        scheduler.shutdownNow();
        device = null;
    }

    /** Fait refuser (statut d'échec) les instances reçues, ou les accepter de nouveau. */
    void refuse(boolean refuse) {
        this.refuse = refuse;
    }

    /** Instances reçues de cette étude. */
    List<Attributes> study(String studyUid) {
        return received.values().stream().filter(a -> studyUid.equals(a.getString(Tag.StudyInstanceUID))).toList();
    }

    /** Instance reçue, ou {@code null}. */
    Attributes instance(String sopInstanceUid) {
        return received.get(sopInstanceUid);
    }

    int size() {
        return received.size();
    }

    @Override
    public void close() {
        stop();
    }

    static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
