/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.paths;

/**
 * A2A service path constants — exposed by agent-service-app controller.a2a.
 *
 * @since 0.1.0
 */
public final class A2AServicePaths {
    /** A2A standard Agent Card path */
    public static final String WELL_KNOWN_AGENT_CARD = "/.well-known/agent-card.json";

    /** Compatible AgentScope / partial SDK path */
    public static final String WELL_KNOWN_AGENT_JSON = "/.well-known/agent.json";

    /** JSON-RPC and prefixed card mount prefix */
    public static final String A2A_PREFIX = "/a2a";

    /** A2A JSON-RPC main entry (with trailing slash) */
    public static final String A2A_JSONRPC = "/a2a/";

    /** A2A JSON-RPC main entry (without trailing slash) */
    public static final String A2A_JSONRPC_NO_SLASH = "/a2a";

    /** Compatible Python a2a_service mount prefix */
    public static final String A2A_WELL_KNOWN_CARD = "/a2a/.well-known/agent-card.json";

    private A2AServicePaths() {
    }
}
