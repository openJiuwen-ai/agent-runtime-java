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

    public static Object payload(QueryChunk chunk) {
        if (chunk.getData() != null) {
            return chunk.getData();
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("type", chunk.getType());
        return fallback;
    }

    public static String toJson(QueryChunk chunk, ObjectMapper objectMapper) throws JsonProcessingException {
        return objectMapper.writeValueAsString(payload(chunk));
    }

    public static String toSseData(QueryChunk chunk, ObjectMapper objectMapper) throws JsonProcessingException {
        return " " + toJson(chunk, objectMapper);
    }

    public static byte[] toSseBytes(QueryChunk chunk, ObjectMapper objectMapper) throws JsonProcessingException {
        return ("data: " + toJson(chunk, objectMapper) + "\n\n").getBytes(StandardCharsets.UTF_8);
    }
}
