/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.openjiuwen.service.spec.dto.QueryChunk;

import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lightweight QueryChunk → List{@code <Part<?>>} mapper. Protocol-layer conversion only — no filtering or business
 * interpretation.
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
        if (QueryChunk.TYPE_REMOTE_AGENT_PROGRESS.equals(chunk.getType()) && data instanceof Map<?, ?> progress
                && progress.get("projection") instanceof Map<?, ?> rawProjection) {
            Map<String, Object> projection = new LinkedHashMap<>();
            rawProjection.forEach((key, value) -> projection.put(String.valueOf(key), value));
            Object content = progress.get("content");
            String text = content instanceof String value ? value : GSON.toJson(content);
            return List.of(new TextPart(text, Map.of("_remote_invocation", projection)));
        }
        if (data instanceof String s) {
            return List.of(new TextPart(s));
        }
        return List.of(new TextPart(GSON.toJson(data)));
    }
}
