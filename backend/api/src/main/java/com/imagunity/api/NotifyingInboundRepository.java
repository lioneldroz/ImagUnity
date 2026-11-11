package com.imagunity.api;

import com.imagunity.domain.inbound.InboundFile;
import com.imagunity.domain.inbound.InboundRepository;
import com.imagunity.domain.inbound.InboundTransfer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** {@link InboundRepository} qui signale aux consoles chaque transfert reçu créé ou modifié (événement {@code inbound}). */
public final class NotifyingInboundRepository implements InboundRepository {

    private final InboundRepository delegate;
    private final ConsoleEvents events;

    public NotifyingInboundRepository(InboundRepository delegate, ConsoleEvents events) {
        this.delegate = delegate;
        this.events = events;
    }

    @Override
    public void saveTransfer(InboundTransfer transfer) {
        delegate.saveTransfer(transfer);
        events.publish("inbound", TransferViews.Inbound.of(transfer));
    }

    @Override
    public void saveFile(InboundFile file) {
        delegate.saveFile(file);
    }

    @Override
    public Optional<InboundFile> findFile(long transportFileId) {
        return delegate.findFile(transportFileId);
    }

    @Override
    public List<InboundFile> findFiles(String peerName, String fileName) {
        return delegate.findFiles(peerName, fileName);
    }

    @Override
    public Optional<InboundTransfer> findTransfer(String peerName, UUID transferId) {
        return delegate.findTransfer(peerName, transferId);
    }

    @Override
    public Optional<InboundTransfer> findTransferByGroup(String peerName, String packageGroupId) {
        return delegate.findTransferByGroup(peerName, packageGroupId);
    }

    @Override
    public List<InboundTransfer> findTransfers(InboundTransfer.State state) {
        return delegate.findTransfers(state);
    }

    @Override
    public List<InboundTransfer> findRecentTransfers(int limit) {
        return delegate.findRecentTransfers(limit);
    }

    @Override
    public Map<InboundTransfer.State, Long> countTransfersByState() {
        return delegate.countTransfersByState();
    }

    @Override
    public List<InboundTransfer> findAwaitingAck() {
        return delegate.findAwaitingAck();
    }
}
