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

    /** Réponse de {@code GET /directory/v1/configurations}. */
    record ConfigurationsResponse(String organization, List<SignedDocument> configurations) {
    }

    private final URI entries;
    private final URI configurations;
    private final URI reports;
    private final HttpClient http;
    private final Duration timeout;

    public DirectoryClient(URI directory, Duration timeout) {
        String base = directory.toString().replaceAll("/+$", "");
        this.entries = URI.create(base + "/directory/v1/entries");
        this.configurations = URI.create(base + "/directory/v1/configurations");
        this.reports = URI.create(base + "/directory/v1/configurations/reports");
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Entrées publiées, non vérifiées : à faire passer par {@link LocalDirectory#synchronize}.
     *
     * @throws DirectoryException si l'annuaire est injoignable ou répond mal
     */
    public List<SignedDocument> fetch() {
        Reply response = send(HttpRequest.newBuilder(entries).timeout(timeout).build(), "Lecture de l'annuaire");
        if (response.statusCode() != 200) {
            throw new DirectoryException("Annuaire : réponse HTTP " + response.statusCode());
        }
        EntriesResponse body = Json.read(response.body(), EntriesResponse.class, "Réponse de l'annuaire");
        return body.entries() == null ? List.of() : body.entries();
    }

    /**
     * Documents de configuration centralisée publiés, non vérifiés : la box les vérifie avec la clé épinglée du
     * responsable.
     *
     * @throws DirectoryException si l'annuaire est injoignable ou répond mal
     */
    public List<SignedDocument> fetchConfigurations() {
        Reply response = send(HttpRequest.newBuilder(configurations).timeout(timeout).build(),
                "Lecture des configurations");
        if (response.statusCode() != 200) {
            throw new DirectoryException("Annuaire : réponse HTTP " + response.statusCode()
                    + " à la lecture des configurations");
        }
        ConfigurationsResponse body = Json.read(response.body(), ConfigurationsResponse.class,
                "Réponse de l'annuaire (configurations)");
        return body.configurations() == null ? List.of() : body.configurations();
    }

    /**
     * Envoie le compte rendu de configuration de cette box (signé par elle).
     *
     * @throws DirectoryException si l'annuaire est injoignable ou refuse le compte rendu
     */
    public void report(SignedDocument report) {
        Reply response = send(HttpRequest.newBuilder(reports).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(report.toJson())).build(), "Compte rendu");
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new DirectoryException("Compte rendu refusé par l'annuaire (HTTP " + response.statusCode() + ") : "
                    + response.body());
        }
    }

    /** Réponse lue avec une limite. */
    private record Reply(int statusCode, String body) {
    }

    /**
     * Plus longue réponse lue : un annuaire compromis ou un intermédiaire ne peut pas épuiser la mémoire de la box
     * (une entrée fait quelques kilo-octets : de quoi lire plusieurs milliers de box).
     */
    static final int MAX_RESPONSE = 32 * 1024 * 1024;

    private Reply send(HttpRequest request, String what) {
        try {
            HttpResponse<java.io.InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (java.io.InputStream in = response.body()) {
                byte[] body = in.readNBytes(MAX_RESPONSE + 1);
                if (body.length > MAX_RESPONSE) {
                    throw new DirectoryException(what + " : réponse de l'annuaire trop longue");
                }
                return new Reply(response.statusCode(), new String(body, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new DirectoryException("Annuaire injoignable : " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectoryException(what + " interrompu(e)", e);
        }
    }
}
