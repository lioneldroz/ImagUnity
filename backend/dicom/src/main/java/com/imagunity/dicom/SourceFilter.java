package com.imagunity.dicom;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collection;

/** Liste d'adresses ou de réseaux (notation CIDR, IPv4 ou IPv6) admis à se connecter à l'entrée DICOM. */
final class SourceFilter {

    private SourceFilter() {
    }

    /** Réseau lu : adresse et longueur du préfixe. */
    record Network(byte[] address, int prefix) {

        boolean contains(InetAddress candidate) {
            byte[] bytes = candidate.getAddress();
            if (bytes.length != address.length) {
                return false;
            }
            int full = prefix / 8;
            if (!Arrays.equals(Arrays.copyOf(bytes, full), Arrays.copyOf(address, full))) {
                return false;
            }
            int rest = prefix % 8;
            if (rest == 0) {
                return true;
            }
            int mask = 0xFF << (8 - rest) & 0xFF;
            return (bytes[full] & mask) == (address[full] & mask);
        }
    }

    /** Lit « 192.168.10.0/24 », « 10.0.0.5 » ou « fd00::/8 » ; adresses littérales seulement (pas de nom). */
    static Network parse(String text) {
        String value = text.strip();
        int slash = value.indexOf('/');
        String host = slash < 0 ? value : value.substring(0, slash);
        if (!host.matches("[0-9.]+|[0-9A-Fa-f:]+")) {
            throw new IllegalArgumentException("Source DICOM invalide (adresse IP ou réseau CIDR attendu) : " + text);
        }
        byte[] address;
        try {
            address = InetAddress.getByName(host).getAddress();
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Source DICOM invalide : " + text, e);
        }
        int prefix;
        try {
            prefix = slash < 0 ? address.length * 8 : Integer.parseInt(value.substring(slash + 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Source DICOM invalide : " + text, e);
        }
        if (prefix < 0 || prefix > address.length * 8) {
            throw new IllegalArgumentException("Source DICOM invalide (préfixe) : " + text);
        }
        return new Network(address, prefix);
    }

    /** Vrai si la liste est vide ou si l'adresse appartient à l'un des réseaux. */
    static boolean allows(Collection<String> sources, InetAddress from) {
        return sources.isEmpty() || sources.stream().map(SourceFilter::parse).anyMatch(n -> n.contains(from));
    }
}
