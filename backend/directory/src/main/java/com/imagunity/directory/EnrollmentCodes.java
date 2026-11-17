package com.imagunity.directory;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Codes d'enrôlement tirés par le responsable, gardés sur son poste avec la clé de l'organisation (fichier lisible par
 * son seul propriétaire) : à chaque code, le nom et les groupes prévus pour la box, sa date d'expiration, et la box qui
 * l'a utilisé.
 *
 * @param codes codes, du plus ancien au plus récent
 */
record EnrollmentCodes(List<Entry> codes) {

    /**
     * Un code.
     *
     * @param code      le code, tel que donné à l'installateur
     * @param name      nom de la box dans l'annuaire
     * @param groups    groupes de la box
     * @param createdAt date de création
     * @param expiresAt date au-delà de laquelle il n'est plus accepté
     * @param usedAt    date d'enrôlement de la box, ou {@code null}
     * @param boxId     box enrôlée avec ce code, ou {@code null}
     * @param boxKey    empreinte de la clé de signature de la box enrôlée avec ce code, ou {@code null} (code pas
     *                  encore utilisé, ou utilisé avant que l'empreinte soit retenue)
     */
    record Entry(String code, String name, List<String> groups, Instant createdAt, Instant expiresAt,
                 @JsonInclude(JsonInclude.Include.NON_NULL) Instant usedAt,
                 @JsonInclude(JsonInclude.Include.NON_NULL) UUID boxId,
                 @JsonInclude(JsonInclude.Include.NON_NULL) String boxKey) {

        Entry {
            groups = groups == null ? List.of() : List.copyOf(groups);
        }

        /** Code pas encore utilisé. */
        Entry(String code, String name, List<String> groups, Instant createdAt, Instant expiresAt) {
            this(code, name, groups, createdAt, expiresAt, null, null, null);
        }

        /**
         * Code utilisé par cette box, avec la clé de signature d'empreinte {@code key} : seule la même box, avec les
         * mêmes clés, pourra encore s'en prévaloir (nouvelle demande après l'enrôlement).
         */
        Entry used(UUID box, String key, Instant at) {
            return new Entry(code, name, groups, createdAt, expiresAt, at, box, key);
        }
    }

    EnrollmentCodes {
        codes = codes == null ? List.of() : List.copyOf(codes);
    }

    static EnrollmentCodes read(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new EnrollmentCodes(List.of());
        }
        return Json.read(Files.readString(file, StandardCharsets.UTF_8), EnrollmentCodes.class, "Fichier des codes");
    }

    void write(Path file) throws IOException {
        String content = Json.write(this);
        if (Files.exists(file)) {
            KeyFiles.replace(file, content);
        } else {
            KeyFiles.writeNew(file, content);
        }
    }

    /** Code dont l'identifiant transmis par une box est celui-ci. */
    Optional<Entry> byId(String id) {
        return id == null ? Optional.empty() : codes.stream().filter(c -> EnrollmentCode.id(c.code()).equals(id))
                .findFirst();
    }

    EnrollmentCodes with(Entry entry) {
        List<Entry> all = new ArrayList<>(codes);
        all.add(entry);
        return new EnrollmentCodes(all);
    }

    /** Retire un code (non utilisé, ou dont on ne veut plus garder la trace). */
    EnrollmentCodes without(Entry entry) {
        return new EnrollmentCodes(codes.stream().filter(c -> !c.equals(entry)).toList());
    }

    /**
     * Tire un nouveau code pour une box à venir.
     *
     * @throws IllegalArgumentException si le nom, les groupes ou la validité sont hors limites
     */
    static Entry create(String name, List<String> groups, long days, Instant now) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 256) {
            throw new IllegalArgumentException("Nom de box vide ou trop long (256 caractères au plus)");
        }
        if (days < 1 || days > 365) {
            throw new IllegalArgumentException("Validité du code : de 1 à 365 jours");
        }
        return new Entry(EnrollmentCode.generate(), n, BoxEntry.groups(groups), now, now.plus(java.time.Duration
                .ofDays(days)));
    }

    EnrollmentCodes replace(Entry before, Entry after) {
        return new EnrollmentCodes(codes.stream().map(c -> c.equals(before) ? after : c).toList());
    }
}
