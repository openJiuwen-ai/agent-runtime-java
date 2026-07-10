/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.openjiuwen.service.spec.dto.QueryChunk;

import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.util.List;

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
        if (data instanceof String s) {
            return List.of(new TextPart(s));
        }
        return List.of(new TextPart(GSON.toJson(data)));
    }
}
