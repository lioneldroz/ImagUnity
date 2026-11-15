package com.imagunity.directory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Lecture des entrées publiées par le service d'annuaire ({@code GET /directory/v1/entries}). */
public final class DirectoryClient {

    /** Réponse du service : organisation et entrée courante de chaque box. */
    record EntriesResponse(String organization, List<SignedDocument> entries) {
    }

    /** Réponse de {@code GET /directory/v1/configurations}. */
    record ConfigurationsResponse(String organization, List<SignedDocument> configurations) {
    }

    /** Réponse de {@code GET /directory/v1/recognitions}. */
    record RecognitionsResponse(String organization, List<SignedDocument> recognitions) {
    }

    private final URI entries;
    private final URI configurations;
    private final URI reports;
    private final URI enrollments;
    private final String base;
    private final HttpClient http;
    private final Duration timeout;

    public DirectoryClient(URI directory, Duration timeout) {
        String base = directory.toString().replaceAll("/+$", "");
        this.entries = URI.create(base + "/directory/v1/entries");
        this.configurations = URI.create(base + "/directory/v1/configurations");
        this.reports = URI.create(base + "/directory/v1/configurations/reports");
        this.enrollments = URI.create(base + "/directory/v1/enrollment-requests");
        this.base = base;
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
        return withoutNulls(body.entries());
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
        return withoutNulls(body.configurations());
    }

    /**
     * Reconnaissances de partenaires publiées par le responsable (fédération), non vérifiées : à faire passer par
     * {@link LocalDirectory#synchronizeRecognitions}. Un service d'annuaire antérieur à la fédération (404) n'en a
     * aucune.
     *
     * @throws DirectoryException si l'annuaire est injoignable ou répond mal
     */
    public List<SignedDocument> fetchRecognitions() {
        Reply response = send(HttpRequest.newBuilder(URI.create(base + "/directory/v1/recognitions")).timeout(timeout)
                .build(), "Lecture des reconnaissances");
        if (response.statusCode() == 404) {
            return List.of(); // service d'annuaire antérieur à la fédération
        }
        if (response.statusCode() != 200) {
            throw new DirectoryException("Annuaire : réponse HTTP " + response.statusCode()
                    + " à la lecture des reconnaissances");
        }
        return withoutNulls(Json.read(response.body(), RecognitionsResponse.class,
                "Réponse de l'annuaire (reconnaissances)").recognitions());
    }

    /**
     * Entrées d'un partenaire reconnu, relayées par l'annuaire de l'organisation, non vérifiées : à faire passer par
     * {@link LocalDirectory#synchronizePartner}.
     *
     * @param partner empreinte de la clé du partenaire
     * @throws DirectoryException si l'annuaire est injoignable ou répond mal
     */
    public List<SignedDocument> fetchPartnerEntries(String partner) {
        if (!partner.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Empreinte de partenaire mal formée");
        }
        Reply response = send(HttpRequest.newBuilder(URI.create(base + "/directory/v1/partners/" + partner
                + "/entries")).timeout(timeout).build(), "Lecture des entrées du partenaire");
        if (response.statusCode() != 200) {
            throw new DirectoryException("Annuaire : réponse HTTP " + response.statusCode()
                    + " à la lecture des entrées du partenaire");
        }
        return withoutNulls(Json.read(response.body(), EntriesResponse.class,
                "Réponse de l'annuaire (entrées du partenaire)").entries());
    }

    /**
     * Preuve de fraîcheur de l'annuaire, non vérifiée : à faire passer par {@link LocalDirectory#synchronizeFreshness}.
     * Vide si l'annuaire n'en publie pas (404).
     *
     * @throws DirectoryException si l'annuaire est injoignable ou répond mal
     */
    public Optional<Freshness> fetchFreshness() {
        return freshness(base + "/directory/v1/freshness");
    }

    /**
     * Preuve de fraîcheur d'un partenaire reconnu, relayée par l'annuaire de l'organisation ; vide si aucune.
     *
     * @param partner empreinte de la clé du partenaire
     * @throws DirectoryException si l'annuaire est injoignable ou répond mal
     */
    public Optional<Freshness> fetchPartnerFreshness(String partner) {
        if (!partner.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Empreinte de partenaire mal formée");
        }
        return freshness(base + "/directory/v1/partners/" + partner + "/freshness");
    }

    private Optional<Freshness> freshness(String url) {
        Reply response = send(HttpRequest.newBuilder(URI.create(url)).timeout(timeout).build(),
                "Lecture de la preuve de fraîcheur");
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new DirectoryException("Annuaire : réponse HTTP " + response.statusCode()
                    + " à la lecture de la preuve de fraîcheur");
        }
        return Optional.of(Freshness.fromJson(response.body()));
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

    /**
     * Transmet la demande d'enrôlement de la box au service d'annuaire, où elle attend la signature du responsable.
     *
     * @return faux si l'annuaire répond que la box est déjà enrôlée
     * @throws DirectoryException si l'annuaire est injoignable ou refuse la demande
     */
    public boolean submitEnrollment(EnrollmentSubmission submission) {
        Reply response = send(HttpRequest.newBuilder(enrollments).timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(submission.toJson())).build(), "Demande d'enrôlement");
        if (response.statusCode() == 409) {
            return false;
        }
        if (response.statusCode() != 202) {
            throw new DirectoryException("Demande d'enrôlement refusée par l'annuaire (HTTP " + response.statusCode()
                    + ") : " + response.body());
        }
        return true;
    }

    /** Cause lisible d'une erreur réseau : certaines (connexion refusée…) n'ont pas de message. */
    static String reason(IOException e) {
        return e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage()
                : "connexion impossible (" + e.getClass().getSimpleName() + ")";
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
            throw new DirectoryException("Annuaire injoignable : " + reason(e), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectoryException(what + " interrompu(e)", e);
        }
    }

    /** Documents publiés, sans les éléments vides d'une réponse mal formée (écartés comme invalides). */
    private static List<SignedDocument> withoutNulls(List<SignedDocument> documents) {
        return documents == null ? List.of() : documents.stream().filter(java.util.Objects::nonNull).toList();
    }
}
