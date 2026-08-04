/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of a durable memory write.
 *
 * @param records records returned by the backing service
 * @param raw raw provider response
 * @since 0.1.0
 */
public record MemoryWriteResult(List<MemoryRecord> records, Map<String, Object> raw) {
    /**
     * Creates a normalized write result.
     */
    public MemoryWriteResult {
        records = records != null ? List.copyOf(records) : List.of();
        raw = copy(raw);
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source != null ? Collections.unmodifiableMap(new LinkedHashMap<>(source)) : Map.of();
    }
}
