package com.imagunity.directory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Lecture et écriture JSON des pièces de l'annuaire ; un champ inconnu est refusé. */
final class Json {

    static final JsonMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Json() {
    }

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    static <T> T read(String text, Class<T> type, String what) {
        try {
            T value = MAPPER.readValue(text, type);
            if (value == null) {
                throw new DirectoryException(what + " vide");
            }
            return value;
        } catch (JacksonException e) {
            throw new DirectoryException(what + " illisible : " + e.getOriginalMessage(), e);
        }
    }
}
