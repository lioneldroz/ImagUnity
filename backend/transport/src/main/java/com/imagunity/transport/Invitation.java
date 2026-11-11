package com.imagunity.transport;

/**
 * Lien d'invitation à usage unique, à transmettre hors bande au partenaire.
 *
 * @param link      lien complet, accepté par {@link TransportPort#acceptInvitation(String)}
 * @param shortLink forme courte du lien (pour un QR code), ou {@code null}
 */
public record Invitation(String link, String shortLink) {

    /** La forme la plus compacte disponible, pour l'affichage. */
    public String preferredLink() {
        return shortLink != null ? shortLink : link;
    }
}
