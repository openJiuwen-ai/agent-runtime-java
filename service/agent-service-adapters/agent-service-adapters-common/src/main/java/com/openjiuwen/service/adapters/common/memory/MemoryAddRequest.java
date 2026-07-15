/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Request to add durable memory.
 *
 * @param scope memory scope
 * @param messages messages to store
 * @param options provider-specific options
 * @since 0.1.0
 */
public record MemoryAddRequest(MemoryScope scope, List<MemoryMessage> messages, Map<String, Object> options) {
    /**
     * Creates a normalized add request.
     */
    public MemoryAddRequest {
        scope = scope != null ? scope : MemoryScope.empty();
        messages = messages != null ? List.copyOf(messages) : List.of();
        options = copy(options);
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source != null ? Collections.unmodifiableMap(new LinkedHashMap<>(source)) : Map.of();
    }
}
