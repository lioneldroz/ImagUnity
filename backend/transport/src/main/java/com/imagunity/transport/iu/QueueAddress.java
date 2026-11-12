package com.imagunity.transport.iu;

import com.imagunity.transport.TransportException;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Adresse de dépôt d'une file IU-MSG, publiée dans l'entrée d'annuaire de la box ({@code transport.iu}) :
 * {@code https://relais.example/iu/v1/queues/<sendId>}.
 *
 * @param relay  adresse du relais
 * @param sendId identifiant de dépôt
 */
public record QueueAddress(URI relay, String sendId) {

    private static final Pattern FORM = Pattern.compile("(https?://[^/?#\\s]+)/iu/v1/queues/([A-Za-z0-9_-]{43})");

    public static QueueAddress parse(String address) {
        Matcher m = address == null ? null : FORM.matcher(address.strip());
        if (m == null || !m.matches()) {
            throw new TransportException("Adresse de file IU-T invalide : " + address);
        }
        return new QueueAddress(URI.create(m.group(1)), m.group(2));
    }

    @Override
    public String toString() {
        return relay + "/iu/v1/queues/" + sendId;
    }
}
