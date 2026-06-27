/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.query;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.spec.dto.QueryChunk;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared SSE serialization helpers for MVC and WebFlux controllers.
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
        if (chunk.getData() != null) {
            return chunk.getData();
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", chunk.getType());
        return fallback;
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
