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

    /** JSON-RPC mount prefix */
    public static final String A2A_PREFIX = "/a2a";

    /** A2A JSON-RPC main entry (with trailing slash) */
    public static final String A2A_JSONRPC = "/a2a/";

    /** A2A JSON-RPC main entry (without trailing slash) */
    public static final String A2A_JSONRPC_NO_SLASH = "/a2a";

    /** Fixed A2A push notification callback receiver path */
    public static final String A2A_PUSH_NOTIFICATION_CALLBACK = "/a2a/push-notifications/callback";

    /** Hosted agent discovery list. */
    public static final String HOSTED_AGENTS = "/a2a/agents";

    /** Hosted JSON-RPC entry; ID syntax is checked after protocol authorization. */
    public static final String HOSTED_AGENT_RPC = HOSTED_AGENTS + "/{agentId}";

    /** Card for one registered agent. */
    public static final String HOSTED_AGENT_CARD = HOSTED_AGENT_RPC + WELL_KNOWN_AGENT_CARD;

    /** Callback bound to the local agent that initiated a remote invocation. */
    public static final String HOSTED_AGENT_CALLBACK = A2A_PUSH_NOTIFICATION_CALLBACK + "/{agentId}";

    private A2AServicePaths() {
    }
}
