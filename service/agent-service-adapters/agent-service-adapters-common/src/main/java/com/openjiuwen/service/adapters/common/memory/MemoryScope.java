/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.memory;

/**
 * Caller-supplied memory scope.
 *
 * @param userId end-user scope id
 * @param agentId agent scope id
 * @param sessionId session scope id
 * @param scopeId business-defined scope id
 * @since 0.1.0
 */
public record MemoryScope(String userId, String agentId, String sessionId, String scopeId) {
    /**
     * Creates a normalized scope.
     */
    public MemoryScope {
        userId = normalize(userId);
        agentId = normalize(agentId);
        sessionId = normalize(sessionId);
        scopeId = normalize(scopeId);
    }

    /**
     * Empty scope. Store implementations may merge it with configured defaults.
     *
     * @return empty scope
     */
    public static MemoryScope empty() {
        return new MemoryScope("", "", "", "");
    }

    private static String normalize(String value) {
        return value != null ? value.trim() : "";
    }
}
