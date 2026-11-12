package com.imagunity.orchestration;

import com.imagunity.directory.BoxEntry;
import com.imagunity.directory.HybridKemPublicKey;
import com.imagunity.directory.LocalDirectory;
import com.imagunity.directory.SealedFiles;
import com.imagunity.domain.peer.Peer;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Chiffrement des paquets avec l'annuaire : un paquet est chiffré pour les clés de chiffrement publiées dans l'entrée
 * de la box destinataire, et déchiffré avec les clés de cette box.
 *
 * <p>Quand le chiffrement est exigé (par défaut), on refuse d'envoyer à une box dont l'entrée ne publie pas de clés de
 * chiffrement (entrée antérieure, à réémettre), et de restituer un paquet reçu en clair. Sinon, ces paquets passent en
 * clair, avec un avertissement : réservé à la migration d'un parc.
 */
public final class DirectoryPacketEncryption implements PacketEncryption {

    private static final System.Logger LOG = System.getLogger(DirectoryPacketEncryption.class.getName());

    private final LocalDirectory directory;
    private final BoxIdentity me;
    private final boolean required;

    public DirectoryPacketEncryption(LocalDirectory directory, BoxIdentity me, boolean required) {
        this.directory = directory;
        this.me = me;
        this.required = required;
    }

    @Override
    public Path seal(Peer recipient, Path packet) throws IOException {
        Optional<HybridKemPublicKey> key = Optional.ofNullable(recipient.boxId()).flatMap(directory::entry)
                .flatMap(BoxEntry::encryptionKey);
        if (key.isEmpty()) {
            String reason = "Box du partenaire « " + recipient.name() + " » sans clés de chiffrement dans l'annuaire "
                    + "(entrée à réémettre à partir d'une nouvelle demande d'enrôlement)";
            if (required) {
                throw new EncryptionRefusedException(reason);
            }
            LOG.log(Level.WARNING, "{0} : paquet envoyé sans chiffrement applicatif", reason);
            return packet;
        }
        Path sealed = packet.resolveSibling(packet.getFileName() + SealedFiles.EXTENSION);
        Files.deleteIfExists(sealed); // reste d'un essai interrompu
        SealedFiles.seal(packet, sealed, key.get(), sealed.getFileName().toString());
        return sealed;
    }

    @Override
    public Path open(Path received, String fileName, Path workDir) throws IOException {
        if (!fileName.endsWith(SealedFiles.EXTENSION)) {
            if (required) {
                throw new IOException("Paquet reçu sans chiffrement applicatif, refusé : le chiffrement est exigé");
            }
            LOG.log(Level.WARNING, "Paquet {0} reçu sans chiffrement applicatif", fileName);
            return received;
        }
        Files.createDirectories(workDir);
        Path plain = workDir.resolve(fileName.substring(0, fileName.length() - SealedFiles.EXTENSION.length()));
        Files.deleteIfExists(plain);
        SealedFiles.open(received, plain, me.encryption(), fileName);
        return plain;
    }
}
