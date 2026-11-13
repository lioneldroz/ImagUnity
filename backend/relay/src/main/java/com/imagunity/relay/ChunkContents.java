package com.imagunity.relay;

import java.io.IOException;
import java.util.OptionalLong;
import org.springframework.core.io.Resource;

/**
 * Contenu des morceaux une fois vérifiés : sur le disque du relais ({@link ChunkFiles}), ou sur un service compatible
 * S3 partagé par plusieurs instances ({@link S3ChunkContents}, spécification § 6.8). Le téléversement est toujours
 * reçu dans un fichier temporaire local et vérifié (taille, empreinte, signature) avant d'être mis en place.
 */
public interface ChunkContents {

    /** Met en place un téléversement vérifié ; le fichier temporaire peut être supprimé ensuite. */
    void commit(ChunkFiles.Received received, String senderId) throws IOException;

    /** Contenu d'un morceau téléversé, de {@code size} octets ({@code Range} pris en charge). */
    Resource open(String senderId, long size) throws IOException;

    /** Supprime un morceau ; sans effet s'il n'existe pas. */
    void delete(String senderId) throws IOException;

    /** Espace libre, s'il est mesurable (disque) ; vide pour un stockage objet. */
    OptionalLong usableSpace() throws IOException;
}
