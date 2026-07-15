/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

/**
 * Message content to persist into long-term memory.
 *
 * @param role message role
 * @param content message content
 * @since 0.1.0
 */
public record MemoryMessage(String role, String content) {
    /**
     * Creates a normalized memory message.
     */
    public MemoryMessage {
        role = normalize(role);
        content = normalize(content);
    }

    private static String normalize(String value) {
        return value != null ? value.trim() : "";
    }
}
