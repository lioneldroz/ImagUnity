package com.imagunity.rules;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.dcm4che3.data.Attributes;

/**
 * Configuration DICOM d'une box décrite hors de sa configuration locale (document de configuration centralisée) :
 * nœuds du site, routes sortantes (d'un nœud source vers des box) et routes entrantes (d'une box émettrice vers des
 * nœuds destinations). Vérifiée et compilée une fois ; la première route qui correspond s'applique.
 *
 * @param aet      AET de la box (appelé par les sources ; sous lequel elle restitue), ou {@code null} : local
 * @param nodes    nœuds, par nom
 * @param outgoing routes sortantes, dans l'ordre
 * @param incoming routes entrantes, dans l'ordre
 */
public record DicomRouting(String aet, Map<String, Node> nodes, List<Outgoing> outgoing, List<Incoming> incoming) {

    /** Nœud DICOM du site. */
    public record Node(String name, String aet, String host, Integer port, boolean source, boolean destination) {
    }

    /**
     * Route sortante.
     *
     * @param fromAets  AET des sources admises (vide : toutes)
     * @param calledAet AET appelé exigé, ou {@code null} : tous ceux de la box
     * @param to        box destinataires
     */
    public record Outgoing(String name, Set<String> fromAets, String calledAet, DicomCondition condition,
                           List<UUID> to) {

        /** Vrai si l'instance, envoyée par {@code callingAet} à {@code calledAet}, suit cette route. */
        public boolean matches(String callingAet, String calledAet, Attributes dataset) {
            return (fromAets.isEmpty() || callingAet != null && fromAets.contains(callingAet.strip()))
                    && (this.calledAet == null || calledAet != null && this.calledAet.equals(calledAet.strip()))
                    && condition.matches(dataset);
        }
    }

    /**
     * Route entrante.
     *
     * @param from box émettrices admises (vide : toutes)
     * @param to   nœuds destinations, par nom
     */
    public record Incoming(String name, Set<UUID> from, DicomCondition condition, List<String> to) {

        public boolean matches(UUID origin, Attributes dataset) {
            return (from.isEmpty() || origin != null && from.contains(origin)) && condition.matches(dataset);
        }
    }

    /** Description d'un nœud, hors du module des documents signés. */
    public record NodeSpec(String name, String aet, String host, Integer port, boolean source, boolean destination) {
    }

    /** Description d'une route : {@code from} et {@code to} sont des noms de nœuds ou des identifiants de box. */
    public record RouteSpec(String name, List<String> from, String calledAet, Map<String, String> and,
                            Map<String, String> or, List<String> to) {
    }

    public DicomRouting {
        nodes = Map.copyOf(nodes);
        outgoing = List.copyOf(outgoing);
        incoming = List.copyOf(incoming);
    }

    /**
     * Vérifie et compile une configuration DICOM.
     *
     * @throws IllegalArgumentException au premier élément invalide (mot-clé DICOM inconnu, nœud absent…)
     */
    public static DicomRouting compile(String aet, List<NodeSpec> nodes, List<RouteSpec> outgoing,
                                       List<RouteSpec> incoming) {
        Map<String, Node> byName = new LinkedHashMap<>();
        for (NodeSpec n : nodes) {
            if (byName.put(n.name(), new Node(n.name(), n.aet(), n.host(), n.port(), n.source(), n.destination()))
                    != null) {
                throw new IllegalArgumentException("Nœud DICOM en double : " + n.name());
            }
        }
        List<Outgoing> out = new ArrayList<>();
        for (RouteSpec r : outgoing) {
            String context = "Route sortante " + r.name();
            Set<String> fromAets = new LinkedHashSet<>();
            for (String from : r.from()) {
                Node node = byName.get(from);
                if (node == null || !node.source()) {
                    throw new IllegalArgumentException(context + " : « " + from + " » n'est pas un nœud source");
                }
                fromAets.add(node.aet());
            }
            out.add(new Outgoing(r.name(), Set.copyOf(fromAets), r.calledAet(),
                    DicomCondition.of(context, r.and(), r.or()), r.to().stream().map(b -> box(b, context)).toList()));
        }
        List<Incoming> in = new ArrayList<>();
        for (RouteSpec r : incoming) {
            String context = "Route entrante " + r.name();
            for (String to : r.to()) {
                Node node = byName.get(to);
                if (node == null || !node.destination()) {
                    throw new IllegalArgumentException(context + " : « " + to + " » n'est pas un nœud destination");
                }
            }
            in.add(new Incoming(r.name(), Set.copyOf(r.from().stream().map(b -> box(b, context)).toList()),
                    DicomCondition.of(context, r.and(), r.or()), List.copyOf(r.to())));
        }
        return new DicomRouting(aet, byName, out, in);
    }

    private static UUID box(String id, String context) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(context + " : identifiant de box invalide « " + id + " »");
        }
    }

    /** Nœud source dont c'est l'AET, s'il existe. */
    public Optional<Node> source(String callingAet) {
        return callingAet == null ? Optional.empty() : nodes.values().stream()
                .filter(n -> n.source() && n.aet().equals(callingAet.strip())).findFirst();
    }

    /**
     * AET appelés acceptés : celui de la box ({@link #aet}, sinon {@code localAet}) et ceux exigés par les routes
     * sortantes.
     */
    public Set<String> calledAets(String localAet) {
        Set<String> aets = new LinkedHashSet<>();
        if (aet != null) {
            aets.add(aet);
        } else if (localAet != null && !localAet.isBlank()) {
            aets.add(localAet.strip());
        }
        outgoing.forEach(r -> {
            if (r.calledAet() != null) {
                aets.add(r.calledAet());
            }
        });
        return aets;
    }

    /** Première route sortante suivie par cette instance. */
    public Optional<Outgoing> outgoingFor(String callingAet, String calledAet, Attributes dataset) {
        return outgoing.stream().filter(r -> r.matches(callingAet, calledAet, dataset)).findFirst();
    }

    /** Première route entrante suivie par cette instance venue de la box {@code origin}. */
    public Optional<Incoming> incomingFor(UUID origin, Attributes dataset) {
        return incoming.stream().filter(r -> r.matches(origin, dataset)).findFirst();
    }

    /** Box désignées par les routes sortantes (à appairer). */
    public Set<UUID> outgoingBoxes() {
        Set<UUID> boxes = new LinkedHashSet<>();
        outgoing.forEach(r -> boxes.addAll(r.to()));
        return boxes;
    }
}
