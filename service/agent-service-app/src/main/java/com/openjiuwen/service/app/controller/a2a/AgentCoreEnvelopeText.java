/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
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
    private static final Gson GSON = new Gson();

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
        if (data instanceof String raw) {
            return parseEnvelope(raw).filter(AgentCoreEnvelopeText::isTerminal)
                    .map(envelope -> businessText(envelope).orElse(raw));
        }
        if (data instanceof Map<?, ?> map && isTerminal(map)) {
            return businessText(map);
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
        if (data instanceof String text) {
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        }
        if (!(data instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Optional<String> fromPayload = map.get("payload") instanceof Map<?, ?> payload
                ? firstText(payload)
                : Optional.empty();
        return fromPayload.isPresent() ? fromPayload : firstText(map);
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

    private static Optional<String> firstText(Map<?, ?> map) {
        for (String key : List.of("content", "delta", "output", "response")) {
            Object value = map.get(key);
            if (value == null || value instanceof Map || value instanceof List) {
                continue;
            }
            String text = String.valueOf(value);
            if (!text.isBlank()) {
                return Optional.of(text);
            }
        }
        return Optional.empty();
    }
}
