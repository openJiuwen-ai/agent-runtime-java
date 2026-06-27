/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.openjiuwen.service.spec.dto.QueryChunk;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.List;
import java.util.Map;

/**
 * Lightweight QueryChunk → List{@code <Part<?>>} mapper.
 * Does NOT depend on Core {@code A2ATransformer}.
 *
 * @since 0.1.0
 */
public class ChunkMapper {

    private static final Gson GSON = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();

    public List<Part<?>> toParts(QueryChunk chunk) {
        if (chunk == null || chunk.getData() == null) return List.of();
        Object data = chunk.getData();
        if (data instanceof String s) return List.of(new TextPart(s));
        if (data instanceof Map<?, ?> m) {
            // Runner chunk: extract payload.(output|content) as readable text.
            // Skip empty text and llm_usage metadata — only emit meaningful output.
            Object payload = m.get("payload");
            if (payload instanceof Map<?, ?> pm) {
                Object text = firstNonNull(pm.get("output"), pm.get("content"));
                if (text instanceof String s && !s.isBlank())
                    return List.of(new TextPart(s));
                if (text instanceof String) return List.of(); // empty → skip
                // No output/content in payload (e.g. llm_usage) → skip
                return List.of();
            }
            Object topContent = firstNonNull(
                    m.get("content"), m.get("delta"), m.get("output"), m.get("response"));
            if (topContent instanceof String s && !s.isBlank())
                return List.of(new TextPart(s));
            // Unrecognised but meaningful: serialize cleanly
            if (!m.isEmpty()) return List.of(new TextPart(GSON.toJson(m)));
            return List.of();
        }
        return List.of(new TextPart(GSON.toJson(data)));
    }

    private static Object firstNonNull(Object... values) {
        for (Object v : values) if (v != null) return v;
        return null;
    }
}
