package com.imagunity.api;

import com.imagunity.domain.inbound.InboundFile;
import com.imagunity.domain.inbound.InboundRepository;
import com.imagunity.domain.inbound.InboundTransfer;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferPart;
import com.imagunity.domain.transfer.TransferRepository;
import com.imagunity.domain.transfer.TransferState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Dépôts en mémoire pour les tests de l'API. */
final class MemoryRepositories {

    private MemoryRepositories() {
    }

    static final class Transfers implements TransferRepository {
        final Map<UUID, Transfer> transfers = new LinkedHashMap<>();
        final Map<UUID, List<TransferPart>> parts = new LinkedHashMap<>();

        @Override
        public void create(Transfer t) {
            transfers.put(t.id(), t);
        }

        @Override
        public void update(Transfer t) {
            transfers.put(t.id(), t);
        }

        @Override
        public Optional<Transfer> find(UUID id) {
            return Optional.ofNullable(transfers.get(id));
        }

        @Override
        public Optional<Transfer> findActive(String study, String calledAet) {
            return transfers.values().stream().filter(t -> t.studyInstanceUid().equals(study)
                    && t.calledAet().equals(calledAet) && !t.state().isTerminal()).findFirst();
        }

        @Override
        public List<Transfer> findByStates(Set<TransferState> states) {
            return transfers.values().stream().filter(t -> states.contains(t.state())).toList();
        }

        @Override
        public List<Transfer> findRecent(int limit) {
            return transfers.values().stream().sorted(Comparator.comparing(Transfer::updatedAt).reversed())
                    .limit(limit).toList();
        }

        @Override
        public Map<TransferState, Long> countByState() {
            Map<TransferState, Long> counts = new EnumMap<>(TransferState.class);
            transfers.values().forEach(t -> counts.merge(t.state(), 1L, Long::sum));
            return counts;
        }

        @Override
        public void saveParts(UUID transferId, List<TransferPart> list) {
            parts.put(transferId, new ArrayList<>(list));
        }

        @Override
        public void updatePart(TransferPart part) {
            parts.get(part.transferId()).replaceAll(p -> p.index() == part.index() ? part : p);
        }

        @Override
        public List<TransferPart> parts(UUID transferId) {
            return List.copyOf(parts.getOrDefault(transferId, List.of()));
        }

        @Override
        public Optional<TransferPart> findPartByTransportFileId(long id) {
            return parts.values().stream().flatMap(List::stream)
                    .filter(p -> p.transportFileId() != null && p.transportFileId() == id).findFirst();
        }
    }

    static final class Inbound implements InboundRepository {
        final Map<Long, InboundFile> files = new LinkedHashMap<>();
        final Map<UUID, InboundTransfer> transfers = new LinkedHashMap<>();

        @Override
        public void saveFile(InboundFile file) {
            files.put(file.transportFileId(), file);
        }

        @Override
        public Optional<InboundFile> findFile(long id) {
            return Optional.ofNullable(files.get(id));
        }

        @Override
        public List<InboundFile> findFiles(String peer, String name) {
            return files.values().stream().filter(f -> f.peerName().equals(peer) && f.fileName().equals(name)).toList();
        }

        @Override
        public void saveTransfer(InboundTransfer t) {
            transfers.put(t.transferId(), t);
        }

        @Override
        public Optional<InboundTransfer> findTransfer(String peer, UUID id) {
            return Optional.ofNullable(transfers.get(id)).filter(t -> t.peerName().equals(peer));
        }

        @Override
        public Optional<InboundTransfer> findTransferByGroup(String peer, String group) {
            return transfers.values().stream()
                    .filter(t -> t.peerName().equals(peer) && t.packageGroupId().equals(group)).findFirst();
        }

        @Override
        public List<InboundTransfer> findTransfers(InboundTransfer.State state) {
            return transfers.values().stream().filter(t -> t.state() == state).toList();
        }

        @Override
        public List<InboundTransfer> findRecentTransfers(int limit) {
            return transfers.values().stream().sorted(Comparator.comparing(InboundTransfer::updatedAt).reversed())
                    .limit(limit).toList();
        }

        @Override
        public Map<InboundTransfer.State, Long> countTransfersByState() {
            Map<InboundTransfer.State, Long> counts = new EnumMap<>(InboundTransfer.State.class);
            transfers.values().forEach(t -> counts.merge(t.state(), 1L, Long::sum));
            return counts;
        }

        @Override
        public List<InboundTransfer> findAwaitingAck() {
            return transfers.values().stream().filter(InboundTransfer::awaitsAck).toList();
        }
    }

    static final class Peers implements PeerRepository {
        final List<Peer> peers = new ArrayList<>();

        @Override
        public void save(Peer peer) {
            peers.add(peer);
        }

        @Override
        public Optional<Peer> findByName(String name) {
            return peers.stream().filter(p -> p.name().equals(name)).findFirst();
        }

        @Override
        public Optional<Peer> findByTransportPeerId(long id) {
            return peers.stream().filter(p -> p.transportPeerId() == id).findFirst();
        }

        @Override
        public List<Peer> findAll() {
            return peers;
        }

        @Override
        public void delete(String name) {
            peers.removeIf(p -> p.name().equals(name));
        }
    }
}
