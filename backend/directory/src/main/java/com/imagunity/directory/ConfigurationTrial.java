package com.imagunity.directory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.UUID;

/**
 * Essai d'une configuration DICOM sur un examen fictif, avec le code de routage de la box : quelle route une instance
 * suivrait. Fourni par l'outil du responsable (jar de l'annuaire, qui embarque les règles), trouvé par
 * {@link ServiceLoader} : la bibliothèque d'annuaire reste sans dépendance DICOM.
 */
public interface ConfigurationTrial {

    /**
     * Route retenue : son nom, son rang (à partir de 1) et ses destinations ; {@code route} vaut {@code null} si aucune
     * ne correspond.
     */
    record Result(String route, int rank, List<String> to) {
    }

    /**
     * Instance envoyée par l'appareil source {@code node} à la box, sous l'AET appelé {@code calledAet} (vide : l'AET
     * de la box).
     *
     * @throws IllegalArgumentException configuration invalide, appareil inconnu ou mot-clé DICOM inconnu
     */
    Result outgoing(CentralConfiguration.Dicom dicom, String node, String calledAet, Map<String, String> fields);

    /**
     * Instance reçue de la box {@code origin} : PACS qui la reçoivent.
     *
     * @throws IllegalArgumentException configuration invalide ou mot-clé DICOM inconnu
     */
    Result incoming(CentralConfiguration.Dicom dicom, UUID origin, Map<String, String> fields);

    /** Essai disponible dans cette installation, s'il l'est. */
    static Optional<ConfigurationTrial> available() {
        return ServiceLoader.load(ConfigurationTrial.class, ConfigurationTrial.class.getClassLoader()).findFirst();
    }
}
