/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable memory record.
 *
 * @param memoryId provider memory id
 * @param memory memory text
 * @param metadata provider metadata
 * @param raw raw provider record for fields not modeled by this SPI
 * @since 0.1.0
 */
public record MemoryRecord(String memoryId, String memory, Map<String, Object> metadata, Map<String, Object> raw) {
    /**
     * Creates a normalized memory record.
     */
    public MemoryRecord {
        memoryId = memoryId != null ? memoryId : "";
        memory = memory != null ? memory : "";
        metadata = copy(metadata);
        raw = copy(raw);
    }

    private static Map<String, Object> copy(Map<String, Object> source) {
        return source != null ? Collections.unmodifiableMap(new LinkedHashMap<>(source)) : Map.of();
    }
}
