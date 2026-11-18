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

    /**
     * Lecture d'une liste de l'annuaire, avec le compte du responsable s'il est connu (lecture réservée) ; sans compte
     * sinon, pour un annuaire en lecture libre.
     */
    HttpResponse<String> read(String path) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(directory + path)).GET();
        String known;
        try {
            known = account.get();
        } catch (RuntimeException e) {
            known = null;
        }
        if (known != null) {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(known
                    .getBytes(StandardCharsets.UTF_8)));
        }
        return send(request, "Lecture");
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

    /**
     * Versions du protocole que la box annonce dans son dernier compte rendu, signé par elle (vérifié avec la clé de
     * son entrée) ; {@code null} sans compte rendu lisible ou sans versions : l'entrée garde alors les siennes.
     */
    java.util.List<String> reportedProtocols(BoxEntry entry) throws IOException {
        return report(entry).filter(r -> !r.protocols().isEmpty()).map(ConfigurationReport::protocols).orElse(null);
    }

    /**
     * Dernier compte rendu de cette box, vérifié avec la clé de son entrée ; vide s'il n'y en a pas, ou s'il est altéré
     * ou signé par une autre box.
     */
    java.util.Optional<ConfigurationReport> report(BoxEntry entry) throws IOException {
        HttpResponse<String> response = get("/directory/v1/configurations/reports/" + entry.boxId(), true);
        if (response.statusCode() != 200) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(ConfigurationReport.verify(SignedDocument.fromJson(response.body()),
                    id -> id.equals(entry.boxId()) ? java.util.Optional.of(entry.signingKey())
                            : java.util.Optional.empty()));
        } catch (RuntimeException e) {
            return java.util.Optional.empty(); // compte rendu altéré ou d'une autre box : ignoré
        }
    }

    /**
     * Entrée publiée de cette box, vérifiée avec la clé de l'organisation ; vide si elle n'en a pas.
     *
     * @throws DirectoryException si l'entrée n'est pas signée par cette organisation, ou si l'annuaire refuse la lecture
     */
    java.util.Optional<BoxEntry> publishedEntry(UUID boxId, String organization, HybridPublicKey organizationKey)
            throws IOException {
        HttpResponse<String> current = get("/directory/v1/entries/" + boxId, false);
        if (current.statusCode() == 404) {
            return java.util.Optional.empty();
        }
        if (current.statusCode() != 200) {
            throw new DirectoryException("Lecture de l'entrée de la box " + boxId + " refusée (HTTP "
                    + current.statusCode() + ")");
        }
        BoxEntry entry = new EntryVerifier(organization, organizationKey, SerialMemory.inMemory(),
                java.time.Clock.systemUTC()).verify(SignedDocument.fromJson(current.body())).entry();
        if (!entry.boxId().equals(boxId)) {
            throw new DirectoryException("Entrée d'une autre box servie pour " + boxId);
        }
        return java.util.Optional.of(entry);
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
