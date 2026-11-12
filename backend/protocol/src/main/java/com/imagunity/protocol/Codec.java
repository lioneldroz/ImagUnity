package com.imagunity.protocol;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Encodages communs : base64url sans remplissage, hexadécimal, SHA-256, JSON strict. */
public final class Codec {

    static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private Codec() {
    }

    /** Lecteur JSON strict (champ inconnu refusé), pour les corps de requête du relais. */
    public static JsonMapper json() {
        return JSON;
    }

    public static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static byte[] fromBase64Url(String text, String what) {
        try {
            return Base64.getUrlDecoder().decode(text);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ProtocolException(what + " : base64url invalide", e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256(bytes));
    }

    public static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static String toJson(Object value) {
        return JSON.writeValueAsString(value);
    }

    static <T> T fromJson(String text, Class<T> type, String what) {
        try {
            T value = JSON.readValue(text, type);
            if (value == null) {
                throw new ProtocolException(what + " vide");
            }
            return value;
        } catch (JacksonException e) {
            throw new ProtocolException(what + " illisible : " + e.getOriginalMessage(), e);
        }
    }
}
