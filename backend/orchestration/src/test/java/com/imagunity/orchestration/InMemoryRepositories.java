package com.imagunity.orchestration;

import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.domain.transfer.Transfer;
import com.imagunity.domain.transfer.TransferPart;
import com.imagunity.domain.transfer.TransferRepository;
import com.imagunity.domain.transfer.TransferState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Dépôts en mémoire, même contrat que les dépôts SQL. */
final class InMemoryRepositories {

    private InMemoryRepositories() {
    }

    static class Transfers implements TransferRepository {
        final Map<UUID, Transfer> transfers = new LinkedHashMap<>();
        final Map<UUID, List<TransferPart>> parts = new LinkedHashMap<>();

        @Override
        public void create(Transfer t) {
            transfers.put(t.id(), t);
        }

        @Override
        public void update(Transfer t) {
            if (transfers.replace(t.id(), t) == null) {
                throw new IllegalStateException("inconnu");
            }
        }

        @Override
        public Optional<Transfer> find(UUID id) {
            return Optional.ofNullable(transfers.get(id));
        }

        @Override
        public Optional<Transfer> findActive(String study, String calledAet, String destination) {
            return transfers.values().stream()
                    .filter(t -> t.studyInstanceUid().equals(study) && t.calledAet().equals(calledAet)
                            && t.destination().equals(destination))
                    .filter(t -> !t.state().isTerminal())
                    .findFirst();
        }

        @Override
        public List<Transfer> findByStates(Set<TransferState> states) {
            return transfers.values().stream().filter(t -> states.contains(t.state())).toList();
        }

        @Override
        public List<Transfer> findRecent(int limit) {
            return transfers.values().stream()
                    .sorted(Comparator.comparing(Transfer::updatedAt).reversed()).limit(limit).toList();
        }

        @Override
        public Map<TransferState, Long> countByState() {
            Map<TransferState, Long> counts = new java.util.EnumMap<>(TransferState.class);
            transfers.values().forEach(t -> counts.merge(t.state(), 1L, Long::sum));
            return counts;
        }

        @Override
        public void saveParts(UUID transferId, List<TransferPart> list) {
            parts.put(transferId, new ArrayList<>(list));
        }

        @Override
        public void updatePart(TransferPart part) {
            List<TransferPart> list = parts.get(part.transferId());
            list.replaceAll(p -> p.index() == part.index() ? part : p);
        }

        @Override
        public List<TransferPart> parts(UUID transferId) {
            return parts.getOrDefault(transferId, List.of()).stream()
                    .sorted(Comparator.comparingInt(TransferPart::index)).toList();
        }

        @Override
        public Optional<TransferPart> findPartByTransportFileId(long fileId) {
            return parts.values().stream().flatMap(List::stream)
                    .filter(p -> p.transportFileId() != null && p.transportFileId() == fileId)
                    .findFirst();
        }

        Transfer only() {
            if (transfers.size() != 1) {
                throw new AssertionError("attendu un seul transfert, trouvé " + transfers.size());
            }
            return transfers.values().iterator().next();
        }
    }

    static final class Inbound implements com.imagunity.domain.inbound.InboundRepository {
        final Map<Long, com.imagunity.domain.inbound.InboundFile> files = new LinkedHashMap<>();
        final Map<String, com.imagunity.domain.inbound.InboundTransfer> transfers = new LinkedHashMap<>();

        @Override
        public void saveFile(com.imagunity.domain.inbound.InboundFile file) {
            files.put(file.transportFileId(), file);
        }

        @Override
        public Optional<com.imagunity.domain.inbound.InboundFile> findFile(long id) {
            return Optional.ofNullable(files.get(id));
        }

        @Override
        public List<com.imagunity.domain.inbound.InboundFile> findFiles(String peer, String name) {
            List<com.imagunity.domain.inbound.InboundFile> list = new ArrayList<>(files.values().stream()
                    .filter(f -> f.peerName().equals(peer) && f.fileName().equals(name)).toList());
            java.util.Collections.reverse(list);
            return list;
        }

        @Override
        public void saveTransfer(com.imagunity.domain.inbound.InboundTransfer t) {
            transfers.put(t.peerName() + "/" + t.transferId(), t);
        }

        @Override
        public Optional<com.imagunity.domain.inbound.InboundTransfer> findTransfer(String peer, UUID id) {
            return Optional.ofNullable(transfers.get(peer + "/" + id));
        }

        @Override
        public Optional<com.imagunity.domain.inbound.InboundTransfer> findTransferByGroup(String peer, String group) {
            return transfers.values().stream()
                    .filter(t -> t.peerName().equals(peer) && t.packageGroupId().equals(group)).findFirst();
        }

        @Override
        public List<com.imagunity.domain.inbound.InboundTransfer> findTransfers(
                com.imagunity.domain.inbound.InboundTransfer.State state) {
            return transfers.values().stream().filter(t -> t.state() == state).toList();
        }

        @Override
        public List<com.imagunity.domain.inbound.InboundTransfer> findRecentTransfers(int limit) {
            return transfers.values().stream()
                    .sorted(Comparator.comparing(com.imagunity.domain.inbound.InboundTransfer::updatedAt).reversed())
                    .limit(limit).toList();
        }

        @Override
        public Map<com.imagunity.domain.inbound.InboundTransfer.State, Long> countTransfersByState() {
            Map<com.imagunity.domain.inbound.InboundTransfer.State, Long> counts =
                    new java.util.EnumMap<>(com.imagunity.domain.inbound.InboundTransfer.State.class);
            transfers.values().forEach(t -> counts.merge(t.state(), 1L, Long::sum));
            return counts;
        }

        @Override
        public List<com.imagunity.domain.inbound.InboundTransfer> findAwaitingAck() {
            return transfers.values().stream().filter(com.imagunity.domain.inbound.InboundTransfer::awaitsAck).toList();
        }
    }

    static final class Peers implements PeerRepository {
        final Map<String, Peer> peers = new LinkedHashMap<>();

        @Override
        public void save(Peer peer) {
            peers.values().removeIf(p -> p.transportPeerId() == peer.transportPeerId());
            peers.put(peer.name(), peer);
        }

        @Override
        public Optional<Peer> findByName(String name) {
            return Optional.ofNullable(peers.get(name));
        }

        @Override
        public Optional<Peer> findByTransportPeerId(long id) {
            return peers.values().stream().filter(p -> p.transportPeerId() == id).findFirst();
        }

        @Override
        public List<Peer> findAll() {
            return List.copyOf(peers.values());
        }

        @Override
        public void delete(String name) {
            peers.remove(name);
        }
    }
}
