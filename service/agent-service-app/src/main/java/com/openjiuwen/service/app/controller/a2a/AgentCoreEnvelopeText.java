/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Extracts user-facing text from terminal AgentCore stream envelopes.
 *
 * @since 0.1.0
 */
public final class AgentCoreEnvelopeText {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

    private static final Set<String> FINAL_ENVELOPE_TYPES = Set.of("answer", "workflow_final");

    private AgentCoreEnvelopeText() {
    }

    /**
     * Extracts terminal business text from an envelope map or its JSON representation.
     *
     * @param data normalized AgentCore output
     * @return terminal business text, or empty for non-terminal data
     */
    public static Optional<String> terminalText(Object data) {
        return terminalValue(data).map(AgentCoreEnvelopeText::stringify);
    }

    /**
     * Extracts the terminal business value while preserving structured payloads.
     *
     * @param data normalized AgentCore output
     * @return terminal business value, or empty for non-terminal data
     */
    public static Optional<Object> terminalValue(Object data) {
        if (data instanceof String raw) {
            return parseEnvelope(raw).filter(AgentCoreEnvelopeText::isTerminal)
                    .map(envelope -> businessValue(envelope).orElse(raw));
        }
        if (data instanceof Map<?, ?> map && isTerminal(map)) {
            return businessValue(map);
        }
        return Optional.empty();
    }

    /**
     * Extracts text from a normalized payload, preferring its nested payload map.
     *
     * @param data normalized output data
     * @return the first recognized non-blank text value
     */
    public static Optional<String> businessText(Object data) {
        return businessValue(data).map(AgentCoreEnvelopeText::stringify);
    }

    private static Optional<Object> businessValue(Object data) {
        if (data instanceof String text) {
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        }
        if (!(data instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Optional<Object> fromPayload = map.get("payload") instanceof Map<?, ?> payload
                ? firstValue(payload)
                : Optional.empty();
        return fromPayload.isPresent() ? fromPayload : firstValue(map);
    }

    private static Optional<Map<String, Object>> parseEnvelope(String raw) {
        try {
            return Optional.ofNullable(GSON.fromJson(raw, MAP_TYPE));
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }
    }

    private static boolean isTerminal(Map<?, ?> envelope) {
        return envelope.get("type") instanceof String type && FINAL_ENVELOPE_TYPES.contains(type);
    }

    static boolean isStreamEnvelope(Object data) {
        return data instanceof Map<?, ?> map && map.get("type") instanceof String && map.containsKey("index")
                && map.get("payload") instanceof Map;
    }

    private static Optional<Object> firstValue(Map<?, ?> map) {
        for (String key : List.of("content", "delta", "output", "response")) {
            Object value = map.get(key);
            if (value == null || value instanceof String text && text.isBlank()) {
                continue;
            }
            return Optional.of(value);
        }
        return Optional.empty();
    }

    private static String stringify(Object value) {
        return value instanceof String text ? text : GSON.toJson(value);
    }
}
