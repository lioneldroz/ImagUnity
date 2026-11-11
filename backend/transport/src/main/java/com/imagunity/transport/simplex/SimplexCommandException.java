package com.imagunity.transport.simplex;

import com.imagunity.transport.TransportException;
import tools.jackson.databind.JsonNode;

/** simplex-chat a refusé une commande (réponse {@code chatCmdError}). */
public class SimplexCommandException extends TransportException {

    private final transient JsonNode chatError;

    public SimplexCommandException(String command, JsonNode chatError) {
        super("Commande simplex-chat refusée (" + commandName(command) + ") : " + describe(chatError));
        this.chatError = chatError;
    }

    /** Erreur détaillée renvoyée par simplex-chat (type {@code ChatError}). */
    public JsonNode chatError() {
        return chatError;
    }

    /** Nom de la commande seul, sans ses arguments (qui peuvent contenir des liens ou des chemins). */
    private static String commandName(String command) {
        int space = command.indexOf(' ');
        return space < 0 ? command : command.substring(0, space);
    }

    private static String describe(JsonNode chatError) {
        String type = chatError.path("type").asString();
        JsonNode detail = chatError.path("errorType");
        if (detail.isMissingNode()) {
            detail = chatError.path("agentError");
        }
        if (detail.isMissingNode()) {
            detail = chatError.path("storeError");
        }
        String detailType = detail.path("type").asString();
        return detailType.isEmpty() ? type : type + "/" + detailType;
    }
}
