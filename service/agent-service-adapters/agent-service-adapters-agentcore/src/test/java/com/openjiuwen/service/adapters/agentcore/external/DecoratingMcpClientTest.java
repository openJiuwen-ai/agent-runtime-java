/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests MCP client decoration behavior for external adapter policies.
 *
 * @since 2026-06-24
 */
class DecoratingMcpClientTest {
    @Test
    void listToolsRetriesFailureAndUsesConfiguredTimeoutWhenCallerDoesNotProvideOne() throws Exception {
        FlakyMcpClient delegate = new FlakyMcpClient(0);
        delegate.failListToolsAttempts = 1;
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.setTimeoutMs(2500);
        policy.getRetry().setMax(1);

        DecoratingMcpClient client = new DecoratingMcpClient(
                McpServerConfig.builder()
                        .serverId("srv-1")
                        .serverName("demo")
                        .serverPath("http://localhost/mcp")
                        .build(),
                delegate,
                policy);

        List<Object> result = client.listTools(McpServerConfig.NO_TIMEOUT);

        assertThat(result).containsExactly("tool");
        assertThat(delegate.listToolsAttempts).isEqualTo(2);
        assertThat(delegate.lastTimeout).isEqualTo(2.5f);
    }

    @Test
    void listToolsUsesHardTimeoutWhenDelegateIgnoresTimeout() {
        FlakyMcpClient delegate = new FlakyMcpClient(0);
        delegate.listToolsDelayMs = 250;
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.setTimeoutMs(50);
        policy.getRetry().setMax(0);

        DecoratingMcpClient client = new DecoratingMcpClient(
                McpServerConfig.builder()
                        .serverId("srv-timeout")
                        .serverName("demo")
                        .serverPath("http://localhost/mcp")
                        .build(),
                delegate,
                policy);

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> client.listTools(McpServerConfig.NO_TIMEOUT))
                .isInstanceOf(ExternalSvcAdapterException.class)
                .extracting("errorCode")
                .isEqualTo(ExternalSvcAdapterErrorCode.MCP_TIMEOUT);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(elapsedMs).isLessThan(200);
    }

    @Test
    void callToolDoesNotRetryByDefaultBecauseItMayHaveSideEffects() {
        FlakyMcpClient delegate = new FlakyMcpClient(Integer.MAX_VALUE);
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.getRetry().setMax(2);

        DecoratingMcpClient client = new DecoratingMcpClient(
                McpServerConfig.builder()
                        .serverId("srv-unsafe")
                        .serverName("demo")
                        .serverPath("http://localhost/mcp")
                        .build(),
                delegate,
                policy);

        assertThatThrownBy(() -> client.callTool("echo", Map.of("text", "hi"), McpServerConfig.NO_TIMEOUT))
                .isInstanceOf(ExternalSvcAdapterException.class)
                .extracting("errorCode")
                .isEqualTo(ExternalSvcAdapterErrorCode.MCP_OUTBOUND_CALL_FAILED);
        assertThat(delegate.callToolAttempts).isEqualTo(1);
    }

    @Test
    void callToolRetriesOnlyWhenExplicitlyEnabled() throws Exception {
        FlakyMcpClient delegate = new FlakyMcpClient(1);
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.setRetryToolCalls(true);
        policy.getRetry().setMax(1);

        DecoratingMcpClient client = new DecoratingMcpClient(
                McpServerConfig.builder()
                        .serverId("srv-retry-tool")
                        .serverName("demo")
                        .serverPath("http://localhost/mcp")
                        .build(),
                delegate,
                policy);

        assertThat(client.callTool("echo", Map.of("text", "hi"), McpServerConfig.NO_TIMEOUT)).isEqualTo("ok");
        assertThat(delegate.callToolAttempts).isEqualTo(2);
    }

    @Test
    void circuitBreakerFailsFastAfterThresholdUntilResetWindowPasses() {
        FlakyMcpClient delegate = new FlakyMcpClient(Integer.MAX_VALUE);
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.getCircuitBreaker().setEnabled(true);
        policy.getCircuitBreaker().setFailureThreshold(1);
        policy.getCircuitBreaker().setResetTimeoutMs(60000);

        DecoratingMcpClient client = new DecoratingMcpClient(
                McpServerConfig.builder()
                        .serverId("srv-2")
                        .serverName("demo")
                        .serverPath("http://localhost/mcp")
                        .build(),
                delegate,
                policy);

        assertThatThrownBy(() -> client.callTool("echo", Map.of(), McpServerConfig.NO_TIMEOUT))
                .isInstanceOf(ExternalSvcAdapterException.class)
                .extracting("errorCode")
                .isEqualTo(ExternalSvcAdapterErrorCode.MCP_OUTBOUND_CALL_FAILED);
        assertThatThrownBy(() -> client.callTool("echo", Map.of(), McpServerConfig.NO_TIMEOUT))
                .isInstanceOf(ExternalSvcAdapterException.class)
                .extracting("errorCode")
                .isEqualTo(ExternalSvcAdapterErrorCode.MCP_CIRCUIT_OPEN);
        assertThat(delegate.callToolAttempts).isEqualTo(1);
    }

    @Test
    void callToolWrapsFinalFailureWithExternalServiceErrorCode() {
        FlakyMcpClient delegate = new FlakyMcpClient(Integer.MAX_VALUE);
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.getRetry().setMax(1);
        policy.setRetryToolCalls(true);

        DecoratingMcpClient client = new DecoratingMcpClient(
                McpServerConfig.builder()
                        .serverId("srv-3")
                        .serverName("demo")
                        .serverPath("http://localhost/mcp")
                        .build(),
                delegate,
                policy);

        assertThatThrownBy(() -> client.callTool("echo", Map.of(), McpServerConfig.NO_TIMEOUT))
                .isInstanceOf(ExternalSvcAdapterException.class)
                .hasMessageContaining("MCP outbound call failed")
                .hasCauseInstanceOf(IllegalStateException.class)
                .cause()
                .hasMessageContaining("boom");
        assertThat(delegate.callToolAttempts).isEqualTo(2);
    }

    private static final class FlakyMcpClient implements McpClient {
        private final int failuresBeforeSuccess;
        private int callToolAttempts;
        private int listToolsAttempts;
        private int failListToolsAttempts;
        private long listToolsDelayMs;
        private float lastTimeout;

        private FlakyMcpClient(int failuresBeforeSuccess) {
            this.failuresBeforeSuccess = failuresBeforeSuccess;
        }

        @Override
        public boolean connect(int retryTimes, float timeout) {
            return true;
        }

        @Override
        public boolean disconnect(float timeout) {
            return true;
        }

        @Override
        public List<Object> listTools(float timeout) {
            listToolsAttempts++;
            lastTimeout = timeout;
            sleepIgnoringInterrupts(listToolsDelayMs);
            if (listToolsAttempts <= failListToolsAttempts) {
                throw new IllegalStateException("list boom");
            }
            return List.of("tool");
        }

        @Override
        public Object callTool(String toolName, Map<String, Object> arguments, float timeout) {
            callToolAttempts++;
            lastTimeout = timeout;
            if (callToolAttempts <= failuresBeforeSuccess) {
                throw new IllegalStateException("boom");
            }
            return "ok";
        }

        @Override
        public Optional<Object> getToolInfo(String toolName, float timeout) {
            return Optional.empty();
        }

        @Override
        public String getServerPath() {
            return "http://localhost/mcp";
        }

        private static void sleepIgnoringInterrupts(long delayMs) {
            long deadline = System.nanoTime() + delayMs * 1_000_000L;
            while (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ignored) {
                    // Keep sleeping to prove the decorator owns the timeout.
                }
                delayMs = (deadline - System.nanoTime()) / 1_000_000L;
            }
        }
    }
}
