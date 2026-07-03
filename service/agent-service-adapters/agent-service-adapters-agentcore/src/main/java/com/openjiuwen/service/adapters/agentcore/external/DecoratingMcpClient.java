/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.service.adapters.common.external.ExternalCallExecutor;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * MCP client decorator that applies timeout, retry, circuit breaker, and audit
 * policies.
 *
 * @since 2026-06-24
 */
public class DecoratingMcpClient implements McpClient {
    private static final float FLOAT_COMPARISON_EPSILON = 0.000001F;

    private final McpServerConfig config;
    private final McpClient delegate;
    private final AgentCoreExternalProperties.McpPolicy policy;
    private final ExternalCallExecutor executor;

    public DecoratingMcpClient(McpServerConfig config, McpClient delegate,
            AgentCoreExternalProperties.McpPolicy policy) {
        this.config = config;
        this.delegate = delegate;
        this.policy = policy != null ? policy : new AgentCoreExternalProperties.McpPolicy();
        this.executor = new ExternalCallExecutor("MCP", serverLabel(), this.policy,
                ExternalSvcAdapterErrorCode.MCP_OUTBOUND_CALL_FAILED, ExternalSvcAdapterErrorCode.MCP_CIRCUIT_OPEN,
                ExternalSvcAdapterErrorCode.MCP_RETRY_INTERRUPTED, ExternalSvcAdapterErrorCode.MCP_TIMEOUT);
    }

    @Override
    public boolean connect(int retryTimes, float timeout) throws Exception {
        return executor.execute("mcp", "connect", true, Map.of("retryTimes", retryTimes),
                () -> delegate.connect(retryTimes, resolveTimeout(timeout)));
    }

    @Override
    public boolean disconnect(float timeout) throws Exception {
        return executor.execute("mcp", "disconnect", false, () -> delegate.disconnect(resolveTimeout(timeout)));
    }

    @Override
    public List<Object> listTools(float timeout) throws Exception {
        return executor.execute("mcp", "tools/list", true, () -> delegate.listTools(resolveTimeout(timeout)));
    }

    @Override
    public List<Object> listResources(float timeout) throws Exception {
        return executor.execute("mcp", "resources/list", true, () -> delegate.listResources(resolveTimeout(timeout)));
    }

    @Override
    public List<Object> readResource(String uri, float timeout) throws Exception {
        return executor.execute("mcp", "resources/read", true, Map.of("uri", uri),
                () -> delegate.readResource(uri, resolveTimeout(timeout)));
    }

    @Override
    public Object callTool(String toolName, Map<String, Object> arguments, float timeout) throws Exception {
        return executor.execute("mcp", "tools/call", policy.isRetryToolCalls(), toolRequest(toolName, arguments),
                () -> delegate.callTool(toolName, arguments, resolveTimeout(timeout)));
    }

    @Override
    public Optional<Object> getToolInfo(String toolName, float timeout) throws Exception {
        return executor.execute("mcp", "tools/get", true, Map.of("toolName", toolName),
                () -> delegate.getToolInfo(toolName, resolveTimeout(timeout)));
    }

    @Override
    public String getServerPath() {
        return delegate.getServerPath();
    }

    private float resolveTimeout(float requestedTimeout) {
        if (isDifferentTimeout(requestedTimeout, McpServerConfig.NO_TIMEOUT) && requestedTimeout > 0) {
            return requestedTimeout;
        }
        if (policy.getTimeoutMs() <= 0) {
            return McpServerConfig.NO_TIMEOUT;
        }
        return policy.getTimeoutMs() / 1000.0f;
    }

    private boolean isDifferentTimeout(float left, float right) {
        return Math.abs(left - right) > FLOAT_COMPARISON_EPSILON;
    }

    private String serverLabel() {
        if (config == null) {
            return "unknown";
        }
        return config.getServerId() + "/" + config.getServerName();
    }

    private static Map<String, Object> toolRequest(String toolName, Map<String, Object> arguments) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("toolName", toolName);
        request.put("arguments", arguments != null ? arguments : Map.of());
        return request;
    }
}
