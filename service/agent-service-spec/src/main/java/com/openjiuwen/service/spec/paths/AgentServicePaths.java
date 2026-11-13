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

    /** Active task query path (DFX-002). */
    public static final String CURRENT_ACTIVE_TASKS = "/v1/current_active_tasks";

    /** Session management list path (issue #117). */
    public static final String SESSIONS = "/api/sessions";

    /** Session detail path prefix; the session id follows as a path segment. */
    public static final String SESSION_DETAIL = "/api/sessions/{sessionId}";

    /** Session messages path; the session id follows as a path segment. */
    public static final String SESSION_MESSAGES = "/api/sessions/{sessionId}/messages";

    /** Session pause path; the session id follows as a path segment. */
    public static final String SESSION_PAUSE = "/api/sessions/{sessionId}/pause";

    /** Session resume path; the session id follows as a path segment. */
    public static final String SESSION_RESUME = "/api/sessions/{sessionId}/resume";

    /** Session terminate path; the session id follows as a path segment. */
    public static final String SESSION_TERMINATE = "/api/sessions/{sessionId}/terminate";

    /** Session compact path; the session id follows as a path segment. */
    public static final String SESSION_COMPACT = "/api/sessions/{sessionId}/compact";

    /** Extension key under which the target session id is exposed to authorizers. */
    public static final String EXTENSION_RESOURCE_ID = "resource.id";

    private AgentServicePaths() {
    }
}
