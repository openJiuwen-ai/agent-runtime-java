/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.core.foundation.tool.mcp.client.StreamableHttpClient;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Map;

/**
 * Verifies MCP timeout and circuit-breaker governance against an independently running FastMCP server.
 *
 * @since 2026-07-14
 */
@Tag("integration")
@EnabledIfSystemProperty(named = "demo.mcp.integration.server-path", matches = ".+")
@ExtendWith(OutputCaptureExtension.class)
class McpGovernanceIntegrationTest {
    private static final String SERVER_PATH_PROPERTY = "demo.mcp.integration.server-path";

    private static final String MCP_ACCEPT = "application/json";

    @Test
    void externalMcpToolErrorDoesNotTripCircuitBeforeTimeout(CapturedOutput output) throws Exception {
        McpServerConfig config = newConfig("demo-mcp-governance");
        StreamableHttpClient delegate = new StreamableHttpClient(config);
        delegate.connect(0, 2.0f);

        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.setTimeoutMs(100);
        policy.getRetry().setMax(0);
        policy.getCircuitBreaker().setEnabled(true);
        policy.getCircuitBreaker().setFailureThreshold(1);
        policy.getCircuitBreaker().setResetTimeoutMs(60000);
        McpClient client = new DecoratingMcpClient(config, delegate, policy);

        try {
            Object toolError = client.callTool("demo_fail", Map.of(), 2.0f);
            assertThat(toolError).asString().contains("demo_fail requested failure");

            Object echoAfterToolError = client.callTool("demo_echo", Map.of("text", "after-tool-error"), 2.0f);
            assertThat(echoAfterToolError).isEqualTo("demo_echo:after-tool-error");

            assertThatThrownBy(
                () -> client.callTool("demo_delay", Map.of("delay_ms", 500), 2.0f))
                .isInstanceOf(ExternalSvcAdapterException.class)
                .extracting("errorCode")
                .isEqualTo(ExternalSvcAdapterErrorCode.MCP_TIMEOUT);
            assertThatThrownBy(
                () -> client.callTool("demo_echo", Map.of("text", "blocked"), 2.0f))
                .isInstanceOf(ExternalSvcAdapterException.class)
                .extracting("errorCode")
                .isEqualTo(ExternalSvcAdapterErrorCode.MCP_CIRCUIT_OPEN);
        } finally {
            delegate.disconnect(2.0f);
        }

        assertThat(output).contains("EXTERNAL_CALL_AUDIT")
            .contains("success=false")
            .contains("code=EXT_MCP_004")
            .contains("method=mcp.tools/call");
    }

    private static McpServerConfig newConfig(String serverId) {
        return McpServerConfig.builder()
            .serverId(serverId)
            .serverName("demo-mcp-tools")
            .serverPath(System.getProperty(SERVER_PATH_PROPERTY))
            .clientType("streamable_http")
            .authHeaders(Map.of("Accept", MCP_ACCEPT))
            .build();
    }
}
