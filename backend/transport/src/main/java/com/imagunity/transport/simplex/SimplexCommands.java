package com.imagunity.transport.simplex;

import java.nio.file.Path;

/**
 * Chaînes de commande de l'API simplex-chat utilisées par ImagUnity.
 *
 * <p>Référence : {@code bots/api/COMMANDS.md} du dépôt simplex-chat.
 */
final class SimplexCommands {

    private SimplexCommands() {
    }

    /** ShowActiveUser : profil actif (fournit le {@code userId}). */
    static String showActiveUser() {
        return "/user";
    }

    /** APIAddContact : crée un lien d'invitation à usage unique. */
    static String createInvitation(long userId) {
        return "/_connect " + userId;
    }

    /** Connect : rejoint le partenaire à partir de son lien. */
    static String connect(String link) {
        requireSingleToken(link, "lien d'invitation");
        return "/connect " + link;
    }

    /** APISendMessages : envoie des messages composés (texte ou fichier) à un contact direct. */
    static String sendMessages(long contactId, String composedMessagesJson) {
        return "/_send @" + contactId + " json " + composedMessagesJson;
    }

    /**
     * ReceiveFile : accepte un fichier annoncé et le télécharge à l'emplacement donné.
     * Le chemin ne doit pas contenir d'espace (la syntaxe de la commande ne prévoit pas de guillemets).
     */
    static String receiveFile(long fileId, Path target) {
        String path = target.toAbsolutePath().toString();
        requireSingleToken(path, "chemin de réception");
        return "/freceive " + fileId + " " + path;
    }

    private static void requireSingleToken(String value, String what) {
        if (value == null || value.isBlank() || value.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("Le " + what + " ne doit être ni vide ni contenir d'espace : " + value);
        }
    }
}
