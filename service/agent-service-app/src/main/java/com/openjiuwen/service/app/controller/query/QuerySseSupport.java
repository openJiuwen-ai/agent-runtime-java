/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.spec.dto.QueryChunk;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared SSE serialization helpers for MVC and WebFlux controllers.
 *
 * @since 2026-07-03
 */
public final class QuerySseSupport {
    private QuerySseSupport() {
    }

    /**
     * Extracts the payload object from a chunk, falling back to a type-only map.
     *
     * @param chunk the query chunk
     * @return the chunk's data or a fallback map
     */
    public static Object payload(QueryChunk chunk) {
        if (QueryChunk.TYPE_REMOTE_AGENT_OUTPUT.equals(chunk.getType())) {
            return remoteEventData((TaskArtifactUpdateEvent) chunk.getData());
        }
        if (chunk.getData() != null) {
            return chunk.getData();
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", chunk.getType());
        return fallback;
    }

    private static Map<String, Object> remoteEventData(TaskArtifactUpdateEvent update) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(RemoteAgentCaller.AGENT_EVENT_METADATA,
                update.artifact().metadata().get(RemoteAgentCaller.AGENT_EVENT_METADATA));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content(update.artifact().parts()));
        data.put("metadata", metadata);
        return data;
    }

    private static Object content(List<Part<?>> parts) {
        if (parts.size() == 1) {
            Part<?> part = parts.get(0);
            if (part instanceof TextPart textPart) {
                return textPart.text();
            }
            if (part instanceof DataPart dataPart) {
                return dataPart.data();
            }
        }
        return parts;
    }

    /**
     * Serializes a chunk's payload to a JSON string.
     *
     * @param chunk the query chunk
     * @param objectMapper the Jackson object mapper
     * @return the JSON string
     * @throws JsonProcessingException if serialization fails
     */
    public static String toJson(QueryChunk chunk, ObjectMapper objectMapper) throws JsonProcessingException {
        return objectMapper.writeValueAsString(payload(chunk));
    }

    /**
     * Builds an SSE data line for the given chunk ({@code " " + json}).
     *
     * @param chunk the query chunk
     * @param objectMapper the Jackson object mapper
     * @return the SSE data line string
     * @throws JsonProcessingException if serialization fails
     */
    public static String toSseData(QueryChunk chunk, ObjectMapper objectMapper) throws JsonProcessingException {
        return " " + toJson(chunk, objectMapper);
    }

    /**
     * Builds a complete SSE frame ({@code "data: ...\n\n"}) for the given chunk.
     *
     * @param chunk the query chunk
     * @param objectMapper the Jackson object mapper
     * @return the SSE frame bytes (UTF-8)
     * @throws JsonProcessingException if serialization fails
     */
    public static byte[] toSseBytes(QueryChunk chunk, ObjectMapper objectMapper) throws JsonProcessingException {
        return ("data: " + toJson(chunk, objectMapper) + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
