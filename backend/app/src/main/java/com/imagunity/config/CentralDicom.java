package com.imagunity.config;

import com.imagunity.configuration.CentralRules;
import com.imagunity.dicom.CentralInput;
import com.imagunity.dicom.DicomRestorer;
import com.imagunity.dicom.DicomValidationException;
import com.imagunity.dicom.RestoreConfig;
import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.domain.peer.Peer;
import com.imagunity.domain.peer.PeerRepository;
import com.imagunity.orchestration.PairingService;
import com.imagunity.rules.DicomRouting;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.dcm4che3.data.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Nœuds et routes DICOM de la configuration centralisée, appliqués par la box sans redémarrage :
 * <ul>
 *   <li>entrée DICOM : sources admises (AET, et adresse si le nœud en a une), AET appelés, routes sortantes vers des
 *       box, désignées par leur identifiant d'annuaire et résolues en contacts appairés ;</li>
 *   <li>restitution : routes entrantes, vers un ou plusieurs PACS selon la box émettrice et le contenu ;</li>
 *   <li>appairage automatique des box désignées par une route sortante, une à la fois, comme depuis la console
 *       (box active, échange permis par l'annuaire et les accords).</li>
 * </ul>
 */
public final class CentralDicom {

    private static final Logger LOG = LoggerFactory.getLogger(CentralDicom.class);
    /** Délai avant de redemander l'appairage d'une même box. */
    static final Duration RETRY = Duration.ofMinutes(10);

    private final Supplier<Optional<DicomRouting>> routing;
    private final PeerRepository peers;
    private final LocalDirectory directory;
    private final UUID self;
    private final PairingService pairing;
    private final String addressKind;
    private final String localAet;
    private final Clock clock;
    private final Map<UUID, Instant> attempts = new ConcurrentHashMap<>();
    private final Echo echo;
    /** Ce que la box a constaté de chaque appareil, par nom de nœud. */
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    /** Intervalle entre deux essais de connexion d'une même destination inchangée. */
    static final Duration ECHO_EVERY = com.imagunity.directory.NodeCheck.ECHO_EVERY;

    /** Essai de connexion DICOM : vide si l'appareil répond, sinon la raison. */
    @FunctionalInterface
    public interface Echo {
        Optional<String> echo(String host, int port, String calledAet, String callingAet);
    }

    /**
     * Constat sur un appareil : dernier essai (pour quelle définition : AET, adresse, port), dernier envoi reçu, dernière
     * connexion refusée.
     */
    private record Seen(String tested, Instant echoAt, String echoError, Instant echoOkAt, Instant receivedAt,
                        Instant refusedAt, String refusedFrom) {
        static final Seen NONE = new Seen(null, null, null, null, null, null, null);
    }

    /**
     * @param routing     configuration DICOM centrale en vigueur
     * @param addressKind clé de l'adresse de contact dans les entrées d'annuaire ({@code iu})
     * @param localAet    AET de la box dans la configuration locale
     */
    public CentralDicom(Supplier<Optional<DicomRouting>> routing, PeerRepository peers, LocalDirectory directory,
                        UUID self, PairingService pairing, String addressKind, String localAet, Clock clock) {
        this(routing, peers, directory, self, pairing, addressKind, localAet, clock,
                (host, port, called, calling) -> com.imagunity.dicom.DicomEcho.echo(host, port, called, calling,
                        com.imagunity.dicom.DicomEcho.TIMEOUT));
    }

    CentralDicom(Supplier<Optional<DicomRouting>> routing, PeerRepository peers, LocalDirectory directory,
                 UUID self, PairingService pairing, String addressKind, String localAet, Clock clock, Echo echo) {
        this.echo = echo;
        this.routing = routing;
        this.peers = peers;
        this.directory = directory;
        this.self = self;
        this.pairing = pairing;
        this.addressKind = addressKind;
        this.localAet = localAet;
        this.clock = clock;
    }

    /** Entrée DICOM centrale, si la configuration appliquée en fixe une. */
    public Optional<CentralInput> input() {
        return routing.get().map(Input::new);
    }

    /** Choix des PACS de restitution, si la configuration appliquée a des routes entrantes. */
    public Optional<DicomRestorer.Router> router() {
        return routing.get().filter(d -> !d.incoming().isEmpty()).map(Router::new);
    }

    private final class Input implements CentralInput {

        private final DicomRouting d;

        Input(DicomRouting d) {
            this.d = d;
        }

        @Override
        public boolean callingAllowed(String callingAet, InetAddress from) {
            Optional<DicomRouting.Node> node = d.source(callingAet);
            if (node.isEmpty()) {
                return false;
            }
            String host = node.get().host();
            String name = node.get().name();
            if (host == null) {
                received(name);
                return true;
            }
            try {
                for (InetAddress address : InetAddress.getAllByName(host)) {
                    if (address.equals(from)) {
                        received(name);
                        return true;
                    }
                }
                LOG.warn("Source {} : connexion depuis {}, attendue depuis {}", callingAet,
                        from == null ? "?" : from.getHostAddress(), host);
            } catch (UnknownHostException e) {
                LOG.warn("Source {} : adresse {} introuvable", callingAet, host);
            }
            refused(name, from == null ? "?" : from.getHostAddress());
            return false;
        }

        @Override
        public boolean calledKnown(String calledAet) {
            return d.calledAets(localAet).contains(calledAet);
        }

        @Override
        public List<String> destinations(String source, String calledAet, Attributes dataset) {
            DicomRouting.Outgoing route = d.outgoingFor(source, calledAet, dataset).orElseThrow(() ->
                    new IllegalArgumentException("Aucune route sortante ne correspond (source " + source + ", AET "
                            + calledAet + ")"));
            List<Peer> paired = peers.findAll();
            List<String> names = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (UUID box : route.to()) {
                paired.stream().filter(p -> box.equals(p.boxId())).findFirst().ifPresentOrElse(
                        p -> names.add(p.name()), () -> missing.add(label(box)));
            }
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("Route " + route.name() + " : box " + String.join(", ", missing)
                        + " pas encore appairée(s) (appairage automatique en cours)");
            }
            return names;
        }

        @Override
        public String describe() {
            return d.nodes().size() + " nœud(s), " + d.outgoing().size() + " route(s) sortante(s)";
        }
    }

    private final class Router implements DicomRestorer.Router {

        private final DicomRouting d;

        Router(DicomRouting d) {
            this.d = d;
        }

        @Override
        public List<RestoreConfig> targets(UUID origin, Attributes dataset) throws DicomValidationException {
            DicomRouting.Incoming route = d.incomingFor(origin, dataset).orElseThrow(() ->
                    new DicomValidationException("Aucune route entrante ne correspond (box émettrice "
                            + (origin == null ? "inconnue" : label(origin)) + ")"));
            String aet = d.aet() != null ? d.aet() : localAet;
            List<RestoreConfig> targets = new ArrayList<>();
            for (String name : route.to()) {
                DicomRouting.Node node = d.nodes().get(name);
                targets.add(new RestoreConfig(node.host(), node.port(), node.aet(), aet));
            }
            return targets;
        }
    }

    private void received(String node) {
        Instant now = clock.instant();
        seen.compute(node, (k, v) -> {
            Seen o = v == null ? Seen.NONE : v;
            return new Seen(o.tested(), o.echoAt(), o.echoError(), o.echoOkAt(), now, o.refusedAt(), o.refusedFrom());
        });
    }

    private void refused(String node, String from) {
        Instant now = clock.instant();
        seen.compute(node, (k, v) -> {
            Seen o = v == null ? Seen.NONE : v;
            return new Seen(o.tested(), o.echoAt(), o.echoError(), o.echoOkAt(), o.receivedAt(), now, from);
        });
    }

    /**
     * Essaie la connexion (C-ECHO) de chaque destination de la configuration en vigueur : une destination nouvelle ou
     * modifiée tout de suite, les autres toutes les {@link #ECHO_EVERY}. La box se présente sous son AET.
     *
     * @return nombre d'essais faits
     */
    public int checkDestinations() {
        return checkDestinations(false);
    }

    /**
     * Comme {@link #checkDestinations()} ; avec {@code force}, toutes les destinations sont essayées tout de suite
     * (demande d'essai du responsable).
     */
    public int checkDestinations(boolean force) {
        Optional<DicomRouting> d = routing.get();
        if (d.isEmpty()) {
            return 0;
        }
        String aet = d.get().aet() != null ? d.get().aet() : localAet;
        Instant now = clock.instant();
        int done = 0;
        for (DicomRouting.Node node : d.get().nodes().values()) {
            if (!node.destination() || node.host() == null || node.port() == null) {
                continue;
            }
            String definition = node.aet() + "@" + node.host() + ":" + node.port() + " par " + aet;
            Seen o = seen.getOrDefault(node.name(), Seen.NONE);
            if (!force && definition.equals(o.tested()) && o.echoAt() != null
                    && now.isBefore(o.echoAt().plus(ECHO_EVERY))) {
                continue;
            }
            Optional<String> error = echo.echo(node.host(), node.port(), node.aet(), aet);
            error.ifPresentOrElse(e -> LOG.warn("Appareil « {} » ({}) : essai de connexion en échec : {}", node.name(),
                    definition, e), () -> LOG.debug("Appareil « {} » : C-ECHO réussi", node.name()));
            Instant at = clock.instant();
            seen.compute(node.name(), (k, v) -> {
                Seen p = v == null ? Seen.NONE : v;
                // Dernier succès : gardé tant que la destination ne change pas (une autre adresse repart de zéro).
                Instant okAt = error.isEmpty() ? at : definition.equals(p.tested()) ? p.echoOkAt() : null;
                return new Seen(definition, at, error.orElse(null), okAt, p.receivedAt(), p.refusedAt(),
                        p.refusedFrom());
            });
            done++;
        }
        return done;
    }

    /** État des appareils de la configuration en vigueur, pour le compte rendu à l'annuaire. */
    public List<com.imagunity.directory.NodeCheck> checks() {
        Optional<DicomRouting> d = routing.get();
        if (d.isEmpty()) {
            return List.of();
        }
        List<com.imagunity.directory.NodeCheck> list = new ArrayList<>();
        for (DicomRouting.Node node : d.get().nodes().values()) {
            Seen o = seen.getOrDefault(node.name(), Seen.NONE);
            boolean tested = node.destination() && o.tested() != null;
            list.add(new com.imagunity.directory.NodeCheck(node.name(), tested ? o.echoAt() : null,
                    tested ? o.echoError() : null, node.source() ? o.receivedAt() : null,
                    node.source() ? o.refusedAt() : null, node.source() ? o.refusedFrom() : null,
                    tested ? o.echoOkAt() : null));
        }
        return list;
    }

    private String label(UUID box) {
        return directory.entry(box).map(e -> "« " + e.displayName() + " »").orElse(box.toString());
    }

    /**
     * Appaire une box désignée par une route sortante et pas encore appairée : une à la fois (l'appairage en cours
     * doit aboutir), et pas plus d'une demande par box toutes les {@link #RETRY}.
     *
     * @return la box dont l'appairage a été demandé
     */
    public Optional<UUID> pairMissing() {
        Optional<DicomRouting> d = routing.get();
        if (d.isEmpty() || pairing.busy()) {
            return Optional.empty();
        }
        List<Peer> paired = peers.findAll();
        Set<String> names = new java.util.HashSet<>();
        paired.forEach(p -> names.add(p.name()));
        Instant now = clock.instant();
        for (UUID box : d.get().outgoingBoxes()) {
            if (box.equals(self) || paired.stream().anyMatch(p -> box.equals(p.boxId()))) {
                continue;
            }
            Instant last = attempts.get(box);
            if (last != null && now.isBefore(last.plus(RETRY))) {
                continue;
            }
            Optional<BoxEntry> entry = directory.entry(box);
            if (entry.isEmpty() || entry.get().statusAt(now) != BoxEntry.Status.ACTIVE) {
                continue;
            }
            Optional<String> refusal = directory.boxRefusal(self, box);
            String address = entry.get().transport().get(addressKind);
            if (refusal.isPresent() || address == null) {
                LOG.warn("Route vers « {} » : appairage impossible ({})", entry.get().displayName(),
                        refusal.orElse("pas d'adresse de contact publiée"));
                attempts.put(box, now);
                continue;
            }
            String name = uniqueName(entry.get().displayName(), names);
            attempts.put(box, now);
            try {
                pairing.join(name, address, box);
                LOG.info("Route vers « {} » : appairage automatique demandé (contact « {} »)",
                        entry.get().displayName(), name);
                return Optional.of(box);
            } catch (RuntimeException e) {
                LOG.warn("Route vers « {} » : appairage en échec ({})", entry.get().displayName(), e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** Nom de contact tiré du nom de la box dans l'annuaire : lettres sans accents, chiffres et tirets. */
    static String uniqueName(String displayName, Set<String> taken) {
        String base = Normalizer.normalize(displayName == null ? "" : displayName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9_-]+", "-").replaceAll("^-+|-+$", "")
                .toLowerCase(java.util.Locale.ROOT);
        if (base.isEmpty()) {
            base = "box";
        }
        base = base.length() > 56 ? base.substring(0, 56) : base;
        String name = base;
        for (int i = 2; taken.contains(name); i++) {
            name = base + "-" + i;
        }
        return name;
    }
}
