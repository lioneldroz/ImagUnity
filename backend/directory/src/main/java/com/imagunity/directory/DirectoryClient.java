package com.imagunity.directory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/** Lecture des entrées publiées par le service d'annuaire ({@code GET /directory/v1/entries}). */
public final class DirectoryClient {

    /** Réponse du service : organisation et entrée courante de chaque box. */
    record EntriesResponse(String organization, List<SignedDocument> entries) {
    }

    private final URI entries;
    private final HttpClient http;
    private final Duration timeout;

    public DirectoryClient(URI directory, Duration timeout) {
        String base = directory.toString().replaceAll("/+$", "");
        this.entries = URI.create(base + "/directory/v1/entries");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Entrées publiées, non vérifiées : à faire passer par {@link LocalDirectory#synchronize}.
     *
     * @throws DirectoryException si l'annuaire est injoignable ou répond mal
     */
    public List<SignedDocument> fetch() {
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(entries).timeout(timeout).build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new DirectoryException("Annuaire injoignable : " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectoryException("Lecture de l'annuaire interrompue", e);
        }
        if (response.statusCode() != 200) {
            throw new DirectoryException("Annuaire : réponse HTTP " + response.statusCode());
        }
        EntriesResponse body = Json.read(response.body(), EntriesResponse.class, "Réponse de l'annuaire");
        return body.entries() == null ? List.of() : body.entries();
    }
}
