package com.imagunity.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.imagunity.dicom.CentralInput;
import com.imagunity.dicom.DicomValidationException;
import com.imagunity.dicom.RestoreConfig;
import com.imagunity.directory.EnrollmentRequest;
import com.imagunity.directory.EntryCache;
import com.imagunity.directory.EntryVerifier;
import com.imagunity.directory.HybridKemKeyPair;
import com.imagunity.directory.HybridKeyPair;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.Organization;
import com.imagunity.directory.SerialMemory;
import com.imagunity.directory.SignedDocument;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.orchestration.PairingService;
import com.imagunity.rules.DicomRouting;
import com.imagunity.transport.DisabledTransport;
import com.imagunity.transport.PeerId;
import com.imagunity.transport.TransportEvent;
import com.imagunity.transport.TransportPort;
import java.net.InetAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.junit.jupiter.api.Test;

/** Nœuds et routes DICOM centraux dans la box : sources, routes vers des box appairées, PACS, appairage auto. */
class CentralDicomTest {

    private static final HybridKeyPair ORG_KEYS = HybridKeyPair.generate();
    private static final Organization ORG = new Organization("chu-exemple", ORG_KEYS);
    private static final UUID SELF = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();
    private static final UUID C = UUID.randomUUID();
    private static final UUID GONE = UUID.randomUUID();

    private static final class MovableClock extends Clock {
        Instant now = Instant.parse("2026-11-17T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MovableClock clock = new MovableClock();
    private final List<Peer> peers = new CopyOnWriteArrayList<>();
    private final List<String> connected = new CopyOnWriteArrayList<>();

    private final PeerRepository repository = new PeerRepository() {
        @Override
        public void save(Peer peer) {
            peers.removeIf(p -> p.name().equals(peer.name()));
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
            return List.copyOf(peers);
        }

        @Override
        public void delete(String name) {
            peers.removeIf(p -> p.name().equals(name));
        }
    };

    private SignedDocument entry(UUID box, String name, boolean withAddress, Instant from) {
        return ORG.enroll(EnrollmentRequest.create(HybridKeyPair.generate(), HybridKemKeyPair.generate().publicKey(),
                box, name, withAddress ? Map.of("iu", "https://relais.example/iu/v1/queues/q" + box.toString()
                        .substring(0, 8)) : Map.of(), from), from, Duration.ofDays(90), 1);
    }

    private LocalDirectory directory() {
        EntryCache none = new EntryCache() {
            @Override
            public void save(UUID boxId, long serial, SignedDocument document, Instant fetchedAt) {
            }

            @Override
            public List<SignedDocument> entries() {
                return List.of();
            }
        };
        LocalDirectory local = new LocalDirectory(new EntryVerifier(ORG.id(), ORG_KEYS.publicKey(),
                SerialMemory.inMemory(), clock), none, clock);
        local.synchronize(() -> List.of(entry(SELF, "Box Nord", true, clock.now), entry(B, "Hôpital Sud — Imagerie",
                true, clock.now), entry(C, "Box C", false, clock.now), entry(GONE, "Box future", true,
                clock.now.plus(Duration.ofDays(1)))));
        return local;
    }

    private static DicomRouting routing(String host) {
        return DicomRouting.compile("IMAGUNITY_NORD", List.of(
                        new DicomRouting.NodeSpec("pacs-nord", "PACS_NORD", "10.2.0.15", 104, true, true),
                        new DicomRouting.NodeSpec("ct-urg", "CT_URG", host, null, true, false),
                        new DicomRouting.NodeSpec("pacs-irm", "PACS_IRM", "10.2.0.16", 11112, false, true)),
                List.of(new DicomRouting.RouteSpec("ct", List.of("ct-urg"), null, Map.of("Modality", "CT"), null,
                                List.of(B.toString(), SELF.toString())),
                        new DicomRouting.RouteSpec("vers-c", List.of(), "IU_TO_C", Map.of(), null,
                                List.of(C.toString(), GONE.toString()))),
                List.of(new DicomRouting.RouteSpec("irm", List.of(B.toString()), null, Map.of("Modality", "MR"), null,
                                List.of("pacs-irm")),
                        new DicomRouting.RouteSpec("reste", List.of(), null, Map.of(), null,
                                List.of("pacs-nord", "pacs-irm"))));
    }

    private static Attributes modality(String m) {
        Attributes a = new Attributes();
        a.setString(Tag.Modality, VR.CS, m);
        return a;
    }

    @Test
    void sourcesRoutesAndPacsComeFromTheCentralConfiguration() throws Exception {
        AtomicReference<Optional<DicomRouting>> current = new AtomicReference<>(Optional.empty());
        PairingService pairing = new PairingService(repository, new DisabledTransport(), clock, Duration.ofMinutes(5));
        CentralDicom dicom = new CentralDicom(current::get, repository, directory(), SELF, pairing, "iu", "LOCAL",
                clock);
        assertThat(dicom.input()).isEmpty();
        assertThat(dicom.router()).isEmpty();
        assertThat(dicom.pairMissing()).isEmpty();

        current.set(Optional.of(routing("127.0.0.1")));
        CentralInput input = dicom.input().orElseThrow();
        InetAddress loopback = InetAddress.getLoopbackAddress();
        assertThat(input.callingAllowed("CT_URG", loopback)).isTrue();
        assertThat(input.callingAllowed("CT_URG", InetAddress.getByName("10.9.9.9"))).as("autre adresse").isFalse();
        assertThat(input.callingAllowed("PACS_NORD", InetAddress.getByName("10.9.9.9")))
                .as("10.2.0.15 attendue").isFalse();
        assertThat(input.callingAllowed("PACS_IRM", loopback)).as("destination seulement").isFalse();
        assertThat(input.callingAllowed("INCONNU", loopback)).isFalse();
        assertThat(input.calledKnown("IMAGUNITY_NORD")).isTrue();
        assertThat(input.calledKnown("IU_TO_C")).isTrue();
        assertThat(input.calledKnown("LOCAL")).isFalse();
        assertThat(input.describe()).contains("3 nœud(s)");

        assertThatThrownBy(() -> input.destinations("CT_URG", "IMAGUNITY_NORD", modality("CT")))
                .hasMessageContaining("« Hôpital Sud — Imagerie »").hasMessageContaining("pas encore appairée");
        assertThatThrownBy(() -> input.destinations("CT_URG", "IMAGUNITY_NORD", modality("MR")))
                .hasMessageContaining("Aucune route sortante");
        peers.add(new Peer("hopital-sud", 7, "Sud", clock.now).boundTo(B));
        peers.add(new Peer("moi-meme", 8, "Nord", clock.now).boundTo(SELF));
        assertThat(input.destinations("CT_URG", "IMAGUNITY_NORD", modality("CT")))
                .containsExactly("hopital-sud", "moi-meme");

        // Restitution : routes entrantes vers un ou deux PACS, sous l'AET central de la box.
        var router = dicom.router().orElseThrow();
        assertThat(router.targets(B, modality("MR"))).containsExactly(
                new RestoreConfig("10.2.0.16", 11112, "PACS_IRM", "IMAGUNITY_NORD"));
        assertThat(router.targets(C, modality("MR"))).extracting(RestoreConfig::calledAet)
                .containsExactly("PACS_NORD", "PACS_IRM");
        current.set(Optional.of(DicomRouting.compile(null, List.of(new DicomRouting.NodeSpec("pacs", "PACS", "h", 1,
                false, true)), List.of(), List.of(new DicomRouting.RouteSpec("seule-b", List.of(B.toString()), null,
                Map.of(), null, List.of("pacs"))))));
        assertThat(dicom.router().orElseThrow().targets(B, modality("CT")).getFirst().callingAet())
                .as("AET local").isEqualTo("LOCAL");
        assertThatThrownBy(() -> dicom.router().orElseThrow().targets(C, modality("CT")))
                .isInstanceOf(DicomValidationException.class).hasMessageContaining("« Box C »");
        assertThatThrownBy(() -> dicom.router().orElseThrow().targets(null, modality("CT")))
                .hasMessageContaining("inconnue");
        assertThatThrownBy(() -> dicom.router().orElseThrow().targets(UUID.randomUUID(), modality("CT")))
                .hasMessageContaining("Aucune route entrante");
        current.set(Optional.of(DicomRouting.compile(null, List.of(), List.of(), List.of())));
        assertThat(dicom.router()).as("sans route entrante : PACS local").isEmpty();
        current.set(Optional.of(routing("adresse-introuvable.invalid")));
        assertThat(dicom.input().orElseThrow().callingAllowed("CT_URG", loopback)).isFalse();
    }

    @Test
    void devicesAreTestedAndWhatTheBoxSeesIsReported() throws Exception {
        AtomicReference<Optional<DicomRouting>> current = new AtomicReference<>(Optional.empty());
        List<String> echoes = new ArrayList<>();
        CentralDicom dicom = new CentralDicom(current::get, repository, directory(), SELF,
                new PairingService(repository, new DisabledTransport(), clock, Duration.ofMinutes(5)), "iu", "LOCAL",
                clock, (host, port, called, calling) -> {
                    echoes.add(called + "@" + host + ":" + port + " par " + calling);
                    return called.equals("PACS_IRM") ? Optional.of("connexion refusée") : Optional.empty();
                });
        assertThat(dicom.checkDestinations()).isZero();
        assertThat(dicom.checks()).isEmpty();

        current.set(Optional.of(routing("127.0.0.1")));
        assertThat(dicom.checkDestinations()).as("deux destinations").isEqualTo(2);
        assertThat(echoes).containsExactlyInAnyOrder("PACS_NORD@10.2.0.15:104 par IMAGUNITY_NORD",
                "PACS_IRM@10.2.0.16:11112 par IMAGUNITY_NORD");
        assertThat(dicom.checkDestinations()).as("déjà essayées").isZero();
        CentralInput input = dicom.input().orElseThrow();
        input.callingAllowed("CT_URG", InetAddress.getLoopbackAddress());
        input.callingAllowed("PACS_NORD", InetAddress.getByName("10.9.9.9"));

        Map<String, com.imagunity.directory.NodeCheck> checks = new java.util.HashMap<>();
        dicom.checks().forEach(c -> checks.put(c.node(), c));
        assertThat(checks.get("pacs-nord").echoOk()).isTrue();
        assertThat(checks.get("pacs-nord").refusedFrom()).isEqualTo("10.9.9.9");
        assertThat(checks.get("pacs-nord").receivedAt()).isNull();
        assertThat(checks.get("pacs-irm").echoError()).isEqualTo("connexion refusée");
        assertThat(checks.get("pacs-irm").receivedAt()).as("pas une source").isNull();
        assertThat(checks.get("ct-urg").echoAt()).as("pas une destination").isNull();
        assertThat(checks.get("ct-urg").receivedAt()).isEqualTo(clock.now);
        String before = checks.get("pacs-irm").state();

        clock.now = clock.now.plus(CentralDicom.ECHO_EVERY);
        echoes.clear();
        assertThat(dicom.checkDestinations()).as("de nouveau après l'intervalle").isEqualTo(2);
        current.set(Optional.of(DicomRouting.compile("IMAGUNITY_NORD", List.of(new DicomRouting.NodeSpec("pacs-irm",
                "PACS_IRM2", "10.2.0.16", 11112, false, true)), List.of(), List.of())));
        echoes.clear();
        assertThat(dicom.checkDestinations()).as("destination modifiée : essayée tout de suite").isEqualTo(1);
        assertThat(dicom.checks()).singleElement().satisfies(c -> {
            assertThat(c.echoOk()).isTrue();
            assertThat(c.state()).isNotEqualTo(before);
        });
    }

    @Test
    void boxesNamedByARouteArePairedOneAtATime() {
        AtomicReference<Optional<DicomRouting>> current = new AtomicReference<>(Optional.of(routing(null)));
        List<String> joined = new ArrayList<>();
        PairingService pairing = new PairingService(repository, transport(connected::add), clock,
                Duration.ofMinutes(5));
        CentralDicom dicom = new CentralDicom(current::get, repository, directory(), SELF, pairing, "iu", "LOCAL",
                clock);
        peers.add(new Peer("hopital-sud", 7, "Sud", clock.now));
        assertThat(dicom.pairMissing()).contains(B);
        assertThat(connected).hasSize(1);
        assertThat(pairing.busy()).isTrue();
        assertThat(dicom.pairMissing()).as("un appairage à la fois").isEmpty();

        clock.now = clock.now.plus(Duration.ofMinutes(6));
        assertThat(pairing.busy()).isFalse();
        // B redemandée seulement après le délai ; C sans adresse, la future pas encore active : rien.
        assertThat(dicom.pairMissing()).isEmpty();
        clock.now = clock.now.plus(CentralDicom.RETRY);
        assertThat(dicom.pairMissing()).contains(B);
        joined.add("ok");

        peers.add(new Peer("b", 9, "B", clock.now).boundTo(B));
        clock.now = clock.now.plus(Duration.ofHours(1));
        assertThat(dicom.pairMissing()).as("déjà appairée").isEmpty();

        PairingService failing = new PairingService(repository, transport(address -> {
            throw new IllegalStateException("relais injoignable");
        }), clock, Duration.ofMinutes(5));
        peers.removeIf(p -> B.equals(p.boxId()));
        assertThat(new CentralDicom(current::get, repository, directory(), SELF, failing, "iu", "LOCAL", clock)
                .pairMissing()).isEmpty();
    }

    /** Transport qui ne fait que noter les connexions demandées. */
    private static TransportPort transport(java.util.function.Consumer<String> connect) {
        return new TransportPort() {
            @Override
            public String contactAddress() {
                return "ici";
            }

            @Override
            public void connect(String contactAddress) {
                connect.accept(contactAddress);
            }

            @Override
            public void sendMessage(PeerId peer, String text) {
            }

            @Override
            public long sendFile(PeerId peer, java.nio.file.Path file) {
                return 0;
            }

            @Override
            public void receiveFile(long fileId, java.nio.file.Path target) {
            }

            @Override
            public void addListener(java.util.function.Consumer<TransportEvent> listener) {
            }

            @Override
            public void close() {
            }
        };
    }

    @Test
    void contactNamesComeFromTheBoxName() {
        assertThat(CentralDicom.uniqueName("Hôpital Sud — Imagerie", Set.of())).isEqualTo("hopital-sud-imagerie");
        assertThat(CentralDicom.uniqueName("Box", Set.of("box", "box-2"))).isEqualTo("box-3");
        assertThat(CentralDicom.uniqueName("—", Set.of())).isEqualTo("box");
        assertThat(CentralDicom.uniqueName(null, Set.of())).isEqualTo("box");
        assertThat(CentralDicom.uniqueName("x".repeat(80), Set.of())).hasSize(56);
    }
}
