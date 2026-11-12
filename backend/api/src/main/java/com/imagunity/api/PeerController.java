package com.imagunity.api;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import com.imagunity.orchestration.PairingService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Partenaires appairés. L'appairage lui-même se fait par l'annuaire ({@code POST /api/v1/directory/boxes/{id}/pair}).
 *
 * <pre>
 * GET  /api/v1/peers   → [{"name", "displayName", "pairedAt", "boxId", "directoryStatus"}]
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/peers")
public class PeerController {

    /**
     * @param boxId           box de l'annuaire à laquelle le partenaire est lié ({@code null} : pas encore vérifié)
     * @param directoryStatus état de cette box dans l'annuaire ({@code null} sans annuaire ou si inconnue)
     */
    public record PeerView(String name, String displayName, Instant pairedAt, UUID boxId,
                           BoxEntry.Status directoryStatus) {
    }

    private final PairingService pairing;
    private final ObjectProvider<LocalDirectory> directory;

    public PeerController(PairingService pairing, ObjectProvider<LocalDirectory> directory) {
        this.pairing = pairing;
        this.directory = directory;
    }

    @GetMapping
    public List<PeerView> peers() {
        LocalDirectory local = directory.getIfAvailable();
        return pairing.peers().stream().map(peer -> view(peer, local)).toList();
    }

    private static PeerView view(Peer peer, LocalDirectory local) {
        BoxEntry.Status status = local == null || peer.boxId() == null ? null : local.status(peer.boxId());
        return new PeerView(peer.name(), peer.displayName(), peer.pairedAt(), peer.boxId(), status);
    }
}
