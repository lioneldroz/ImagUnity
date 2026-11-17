package com.imagunity.dicom;

import java.net.InetAddress;
import java.util.List;
import org.dcm4che3.data.Attributes;

/**
 * Entrée DICOM décrite par la configuration centralisée : sources admises, AET appelés acceptés, et routes d'une
 * source vers des box. Quand elle est présente, elle remplace les émetteurs autorisés, les destinations et le routage
 * de la configuration locale ; elle est relue à chaque association et à chaque instance (changement sans redémarrage).
 */
public interface CentralInput {

    /** Vrai si cette source (AET appelant, adresse de connexion) peut envoyer à la box. */
    boolean callingAllowed(String callingAet, InetAddress from);

    /** Vrai si la box accepte cet AET appelé. */
    boolean calledKnown(String calledAet);

    /**
     * Partenaires (contacts appairés) qui reçoivent cette instance.
     *
     * @param source émetteur : AET appelant (C-STORE) ou {@code dicomweb:<compte>} (STOW-RS)
     * @throws IllegalArgumentException si aucune route ne correspond, ou si une box désignée n'est pas encore
     *                                  appairée : l'instance est refusée avec ce message
     */
    List<String> destinations(String source, String calledAet, Attributes dataset);

    /** Résumé pour les journaux. */
    String describe();
}
