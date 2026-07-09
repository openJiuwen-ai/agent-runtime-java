/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Request to search durable memory.
 *
 * @param scope memory scope
 * @param query semantic search query
 * @param topK maximum number of records
 * @param shouldRerank optional rerank override
 * @param options provider-specific options
 * @since 0.1.0
 */
public record MemorySearchRequest(MemoryScope scope, String query, int topK, Boolean shouldRerank,
    Map<String, Object> options) {
    /**
     * Creates a normalized search request.
     */
    public MemorySearchRequest {
        scope = scope != null ? scope : MemoryScope.empty();
        query = query != null ? query.trim() : "";
        options = copy(options);
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source != null ? Collections.unmodifiableMap(new LinkedHashMap<>(source)) : Map.of();
    }
}
