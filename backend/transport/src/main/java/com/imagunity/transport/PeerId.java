package com.imagunity.transport;

/**
 * Identifiant local d'un partenaire appairé (box distante).
 *
 * <p>Il n'a de sens que pour la box qui l'a attribué : chaque box numérote
 * ses partenaires à sa façon.
 */
public record PeerId(long value) {

    @Override
    public String toString() {
        return "peer-" + value;
    }
}
