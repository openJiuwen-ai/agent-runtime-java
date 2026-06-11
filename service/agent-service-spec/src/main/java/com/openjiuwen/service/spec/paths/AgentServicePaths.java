package com.openjiuwen.service.spec.paths;

/**
 * HTTP path constants for Agent Service (C-013 / Issue #3).
 */
public final class AgentServicePaths {

    public static final String QUERY_V1 = "/v1/query";
    public static final String QUERY_LEGACY = "/query";
    public static final String QUERY_V1_REACTIVE = "/v1/query/reactive";

    public static final String RESET_CONVERSATION_V1 = "/v1/reset_conversation";
    public static final String RESET_CONVERSATION_LEGACY = "/reset_conversation";

    private AgentServicePaths() {
    }
}
