/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extracts the final business text from a remote agent's answer envelope.
 *
 * <p>Remote agents stream intermediate chunks as plain strings, then emit a
 * final chunk whose payload is a JSON envelope of shape
 * {@code {"type":"answer","payload":{"content":"..."}}}. This utility parses the
 * envelope and extracts the business text from a small set of well-known
 * payload keys ({@code content}, {@code delta}, {@code output}, {@code response}).
 *
 * <p>Lifting this logic to the SPI package keeps the orchestrator (runtime core)
 * from depending on {@link DefaultRemoteAgentCaller} internals and lets any
 * {@link RemoteAgentCaller} implementation reuse the same extraction contract.
 *
 * @since 0.1.0
 */
public final class RemoteAgentAnswerExtractor {
    private static final String ANSWER_ENVELOPE_TYPE = "answer";
    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    private RemoteAgentAnswerExtractor() {
    }

    /**
     * Attempts to extract the final business answer from a raw chunk payload.
     *
     * @param raw the raw chunk data, typically a JSON envelope string
     * @return the extracted answer text, or {@link Optional#empty()} if {@code raw}
     *         is not an answer envelope
     */
    public static Optional<String> extractAnswer(String raw) {
        return parseEnvelope(raw)
                .filter(envelope -> ANSWER_ENVELOPE_TYPE.equals(envelope.get("type")))
                .map(envelope -> extractBusinessText(envelope).orElse(raw));
    }

    /**
     * Attempts to extract the final business answer from a Map answer envelope
     * (shape {@code {"type":"answer", ...}}) emitted directly as a chunk payload,
     * as opposed to a JSON-string envelope. Used by callers that stream Map
     * envelopes (e.g. in-process or gateway callers) rather than JSON strings.
     *
     * @param envelope the chunk data as a Map
     * @return the extracted answer text, or {@link Optional#empty()} if
     *         {@code envelope} is not an answer envelope or carries no business text
     */
    public static Optional<String> extractAnswerFromMap(Map<?, ?> envelope) {
        if (envelope == null || !ANSWER_ENVELOPE_TYPE.equals(envelope.get("type"))) {
            return Optional.empty();
        }
        return extractBusinessText(envelope);
    }

    /**
     * Extracts business text from an envelope's {@code payload} (or the envelope
     * itself when the payload is not a map), checking well-known keys.
     *
     * @param data the envelope payload (map or primitive)
     * @return the first non-blank text found, or {@link Optional#empty()}
     */
    static Optional<String> extractBusinessText(Object data) {
        if (data instanceof String s) {
            return s.isBlank() ? Optional.empty() : Optional.of(s);
        }
        if (!(data instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Optional<String> fromPayload = map.get("payload") instanceof Map<?, ?> payload
                ? firstText(payload) : Optional.empty();
        return fromPayload.isPresent() ? fromPayload : firstText(map);
    }

    private static Optional<Map<String, Object>> parseEnvelope(String raw) {
        try {
            return Optional.ofNullable(GSON.fromJson(raw, MAP_TYPE));
        } catch (JsonSyntaxException e) {
            return Optional.empty();
        }
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
