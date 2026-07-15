/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

/**
 * Request to get one durable memory record.
 *
 * @param scope memory scope
 * @param memoryId memory id
 * @since 0.1.0
 */
public record MemoryGetRequest(MemoryScope scope, String memoryId) {
    /**
     * Creates a normalized get request.
     */
    public MemoryGetRequest {
        scope = scope != null ? scope : MemoryScope.empty();
        memoryId = memoryId != null ? memoryId.trim() : "";
    }
}
