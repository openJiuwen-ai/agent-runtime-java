/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.openjiuwen.service.spec.dto.QueryChunk;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight QueryChunk → List{@code <Part<?>>} mapper. Terminal AgentCore envelopes are projected as user-facing
 * text; structured intermediate chunks remain JSON objects in {@link DataPart}.
 *
 * @since 0.1.0
 */
public class ChunkMapper {
    private static final Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    /**
     * Converts a {@link QueryChunk} to a list of A2A SDK {@link Part} objects.
     *
     * @param chunk the query chunk to convert
     * @return the list of parts, never null
     */
    public List<Part<?>> toParts(QueryChunk chunk) {
        if (chunk == null || chunk.getData() == null) {
            return List.of();
        }
        Object data = chunk.getData();
        if (QueryChunk.TYPE_REMOTE_AGENT_OUTPUT.equals(chunk.getType()) && data instanceof Map<?, ?> output
                && output.get("projection") instanceof Map<?, ?> rawProjection) {
            Map<String, Object> projection = new LinkedHashMap<>();
            rawProjection.forEach((key, value) -> projection.put(String.valueOf(key), value));
            return List.of(toPart(output.get("content"), Map.of("_remote_invocation", projection)));
        }
        return List.of(toPart(data, null));
    }

    /**
     * Determines whether a normal AgentCore chunk carries a terminal business result.
     * Remote output projections are deliberately excluded because they are progress of
     * a nested invocation, not the parent agent's terminal result.
     *
     * @param chunk query chunk to inspect
     * @return true when the chunk is an AgentCore terminal result
     */
    public boolean isTerminalResult(QueryChunk chunk) {
        return chunk != null && QueryChunk.TYPE_CHUNK.equals(chunk.getType())
                && AgentCoreEnvelopeText.terminalValue(chunk.getData()).isPresent();
    }

    private static Part<?> toPart(Object data, Map<String, Object> metadata) {
        Optional<Object> terminalValue = AgentCoreEnvelopeText.terminalValue(data);
        if (terminalValue.isPresent()) {
            return toBusinessPart(terminalValue.get(), metadata);
        }
        Object structured = data instanceof String text ? parseStructuredJson(text).orElse(null) : data;
        if (structured instanceof Map || structured instanceof List || structured instanceof Number
                || structured instanceof Boolean) {
            return metadata == null ? new DataPart(structured) : new DataPart(structured, metadata);
        }
        String text = data instanceof String value ? value : GSON.toJson(data);
        return metadata == null ? new TextPart(text) : new TextPart(text, metadata);
    }

    private static Part<?> toBusinessPart(Object value, Map<String, Object> metadata) {
        if (value instanceof Map || value instanceof List || value instanceof Number || value instanceof Boolean) {
            return metadata == null ? new DataPart(value) : new DataPart(value, metadata);
        }
        String text = value instanceof String stringValue ? stringValue : GSON.toJson(value);
        return metadata == null ? new TextPart(text) : new TextPart(text, metadata);
    }

    private static Optional<Object> parseStructuredJson(String text) {
        try {
            Object value = GSON.fromJson(text, Object.class);
            return value instanceof Map || value instanceof List ? Optional.of(value) : Optional.empty();
        } catch (JsonParseException e) {
            return Optional.empty();
        }
    }
}
