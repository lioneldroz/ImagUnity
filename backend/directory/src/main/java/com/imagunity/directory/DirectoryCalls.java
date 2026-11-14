package com.imagunity.directory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.function.Supplier;

/** Appels de l'outil du responsable au service d'annuaire ; le compte de dépôt n'est demandé qu'au besoin. */
final class DirectoryCalls {

    private final String directory;
    private final Supplier<String> account;

    DirectoryCalls(String directory, Supplier<String> account) {
        this.directory = directory.replaceAll("/+$", "");
        this.account = account;
    }

    String directory() {
        return directory;
    }

    /** Lecture ; avec le compte du responsable si {@code withAccount}. */
    HttpResponse<String> get(String path, boolean withAccount) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(directory + path)).GET();
        return send(withAccount ? authorized(request) : request, "Lecture");
    }

    /** Dépôt d'un document signé, avec le compte du responsable. */
    HttpResponse<String> post(String path, SignedDocument document) throws IOException {
        return send(authorized(HttpRequest.newBuilder(URI.create(directory + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(document.toJson()))), "Publication");
    }

    /** Suppression, avec le compte du responsable. */
    HttpResponse<String> delete(String path) throws IOException {
        return send(authorized(HttpRequest.newBuilder(URI.create(directory + path)).DELETE()), "Suppression");
    }

    /** Numéro de la prochaine entrée de cette box : 1, ou le suivant de son entrée publiée. */
    long nextSerial(UUID boxId) throws IOException {
        HttpResponse<String> current = get("/directory/v1/entries/" + boxId, false);
        if (current.statusCode() == 404) {
            return 1;
        }
        if (current.statusCode() != 200) {
            throw new DirectoryException("Lecture de l'entrée de la box " + boxId + " refusée (HTTP "
                    + current.statusCode() + ")");
        }
        return Json.read(SignedDocument.fromJson(current.body()).payload(), BoxEntry.class, "Entrée de box").serial()
                + 1;
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder request) {
        return request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(account.get()
                .getBytes(StandardCharsets.UTF_8)));
    }

    private static HttpResponse<String> send(HttpRequest.Builder request, String what) throws IOException {
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(what + " interrompue", e);
        }
    }
}
