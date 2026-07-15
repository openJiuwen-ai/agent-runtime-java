/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

/**
 * Request to delete one durable memory record.
 *
 * @param scope memory scope
 * @param memoryId memory id
 * @since 0.1.0
 */
public record MemoryDeleteRequest(MemoryScope scope, String memoryId) {
    /**
     * Creates a normalized delete request.
     */
    public MemoryDeleteRequest {
        scope = scope != null ? scope : MemoryScope.empty();
        memoryId = memoryId != null ? memoryId.trim() : "";
    }
}
