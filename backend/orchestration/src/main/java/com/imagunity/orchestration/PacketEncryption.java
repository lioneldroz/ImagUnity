package com.imagunity.orchestration;

import com.imagunity.directory.SealedFiles;
import com.imagunity.domain.peer.Peer;
import java.io.IOException;
import java.nio.file.Path;

/**
 * Chiffrement applicatif des paquets, de bout en bout entre box : en plus du chiffrement du transport, un paquet est
 * chiffré pour la box destinataire, avec les clés publiées dans son entrée d'annuaire. Les relais n'en voient que
 * le chiffré, même si le transport est compromis.
 */
public interface PacketEncryption {

    /**
     * Chiffre un paquet pour ce destinataire.
     *
     * @return le fichier à envoyer : le paquet chiffré ({@code <nom>.iu1}, à côté du paquet en clair, que l'appelant
     *         supprime une fois le chiffré enregistré), ou le paquet lui-même s'il part sans chiffrement applicatif
     * @throws EncryptionRefusedException si l'on ne peut pas chiffrer pour ce destinataire alors que c'est exigé
     */
    Path seal(Peer recipient, Path packet) throws IOException;

    /**
     * Rend un paquet reçu lisible.
     *
     * @param fileName nom du fichier reçu, tel qu'annoncé
     * @param workDir  dossier de travail, où écrire le paquet déchiffré
     * @return le paquet en clair : déchiffré dans {@code workDir}, ou le fichier reçu s'il était en clair et admis
     * @throws IOException si le paquet ne peut pas être déchiffré, ou s'il est en clair alors que le chiffrement est
     *                     exigé
     */
    Path open(Path received, String fileName, Path workDir) throws IOException;

    /** Sans annuaire : paquets en clair (le transport reste chiffré) ; un paquet chiffré ne peut pas être ouvert. */
    static PacketEncryption none() {
        return new PacketEncryption() {
            @Override
            public Path seal(Peer recipient, Path packet) {
                return packet;
            }

            @Override
            public Path open(Path received, String fileName, Path workDir) throws IOException {
                if (fileName.endsWith(SealedFiles.EXTENSION)) {
                    throw new IOException("Paquet chiffré, mais l'annuaire de cette box est désactivé : "
                            + "impossible de le déchiffrer");
                }
                return received;
            }
        };
    }

    /** Chiffrement impossible pour un destinataire alors qu'il est exigé. */
    final class EncryptionRefusedException extends IOException {

        public EncryptionRefusedException(String message) {
            super(message);
        }
    }
}
