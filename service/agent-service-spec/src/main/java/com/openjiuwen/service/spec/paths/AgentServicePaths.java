/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.paths;

/**
 * HTTP path constants for Agent Service (C-013 / Issue #3).
 *
 * @since 0.1.0
 */
public final class AgentServicePaths {
    /** Health probe path. */
    public static final String HEALTH = "/health";

    /** Query v1 path. */
    public static final String QUERY_V1 = "/v1/query";

    /** Legacy query path. */
    public static final String QUERY_LEGACY = "/query";

    /** Reactive query path. */
    public static final String QUERY_V1_REACTIVE = "/v1/query/reactive";

    /** Reset conversation v1 path. */
    public static final String RESET_CONVERSATION_V1 = "/v1/reset_conversation";

    /** Legacy reset conversation path. */
    public static final String RESET_CONVERSATION_LEGACY = "/reset_conversation";

    private AgentServicePaths() {
    }
}
