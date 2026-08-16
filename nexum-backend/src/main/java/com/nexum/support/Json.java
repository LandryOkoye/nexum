package com.nexum.support;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds the small JSON payloads carried by events and checkpoints.
 *
 * <p>Exists because the obvious alternative - concatenating strings with quotes
 * - produces invalid JSON the moment a payload carries a memory excerpt, a task
 * title, or a model-authored reason, all of which routinely contain quotes,
 * newlines, and backslashes. Those payloads are inserted into a {@code JSONB}
 * column, so malformed JSON is not a cosmetic problem: the insert fails, and
 * because {@code EventLog} deliberately swallows its own failures, the event
 * would vanish silently. The recovery timeline is the submission's proof, so an
 * event that disappears under an unlucky apostrophe is a real risk.
 *
 * <p>Static rather than a bean: it is stateless, and event payloads are built at
 * enough call sites that constructor-injecting a formatter everywhere would add
 * noise without buying anything.
 */
public final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /**
     * Builds a JSON object from alternating key/value pairs. Null values are
     * kept rather than dropped - "this event had no checkpoint" is information
     * worth seeing in the timeline, not an absence to hide.
     */
    public static String object(Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("expected alternating key/value pairs, got "
                    + keyValues.length + " arguments");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            fields.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return write(fields);
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("could not serialise " + value.getClass(), ex);
        }
    }
}
