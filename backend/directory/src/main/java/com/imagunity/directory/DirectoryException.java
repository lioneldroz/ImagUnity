package com.imagunity.directory;

/** Pièce de l'annuaire invalide : signature fausse, autre organisation, retour en arrière, format illisible… */
public class DirectoryException extends RuntimeException {

    public DirectoryException(String message) {
        super(message);
    }

    public DirectoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
