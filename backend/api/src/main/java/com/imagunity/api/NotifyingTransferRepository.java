package com.imagunity.api;

import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferPart;
import com.imagunity.domain.transfer.TransferRepository;
import com.imagunity.domain.transfer.TransferState;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** {@link TransferRepository} qui signale aux consoles chaque transfert créé ou modifié (événement {@code transfer}). */
public final class NotifyingTransferRepository implements TransferRepository {

    private final TransferRepository delegate;
    private final ConsoleEvents events;
    private final ConsoleProperties properties;
    private final Clock clock;

    public NotifyingTransferRepository(TransferRepository delegate, ConsoleEvents events, ConsoleProperties properties,
                                       Clock clock) {
        this.delegate = delegate;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void create(Transfer transfer) {
        delegate.create(transfer);
        notify(transfer);
    }

    @Override
    public void update(Transfer transfer) {
        delegate.update(transfer);
        notify(transfer);
    }

    @Override
    public void updatePart(TransferPart part) {
        delegate.updatePart(part);
        delegate.find(part.transferId()).ifPresent(this::notify); // avancement du dépôt
    }

    private void notify(Transfer transfer) {
        events.publish("transfer", TransferViews.Outbound.of(transfer, clock.instant(), properties.ackOverdue()));
    }

    @Override
    public Optional<Transfer> find(UUID id) {
        return delegate.find(id);
    }

    @Override
    public Optional<Transfer> findActive(String studyInstanceUid, String calledAet, String destination) {
        return delegate.findActive(studyInstanceUid, calledAet, destination);
    }

    @Override
    public List<Transfer> findByStates(Set<TransferState> states) {
        return delegate.findByStates(states);
    }

    @Override
    public List<Transfer> findRecent(int limit) {
        return delegate.findRecent(limit);
    }

    @Override
    public Map<TransferState, Long> countByState() {
        return delegate.countByState();
    }

    @Override
    public void saveParts(UUID transferId, List<TransferPart> parts) {
        delegate.saveParts(transferId, parts);
    }

    @Override
    public List<TransferPart> parts(UUID transferId) {
        return delegate.parts(transferId);
    }

    @Override
    public Optional<TransferPart> findPartByTransportFileId(long transportFileId) {
        return delegate.findPartByTransportFileId(transportFileId);
    }
}
