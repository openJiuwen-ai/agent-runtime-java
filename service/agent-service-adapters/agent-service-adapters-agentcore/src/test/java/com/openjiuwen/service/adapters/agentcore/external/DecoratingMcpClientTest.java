/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.foundation.tool.mcp.McpClient;
import com.openjiuwen.core.foundation.tool.mcp.McpServerConfig;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tests MCP client decoration behavior for external adapter policies.
 *
 * @since 2026-06-24
 */
@ExtendWith(OutputCaptureExtension.class)
class DecoratingMcpClientTest {
    @Test
    void listToolsRetriesFailureAndUsesConfiguredTimeoutWhenCallerDoesNotProvideOne() throws Exception {
        FlakyMcpClient delegate = new FlakyMcpClient(0);
        delegate.failListToolsAttempts = 1;
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.setTimeoutMs(2500);
        policy.getRetry().setMax(1);

        DecoratingMcpClient client = new DecoratingMcpClient(
            McpServerConfig.builder().serverId("srv-1").serverName("demo").serverPath("http://localhost/mcp").build(),
            delegate, policy);

        List<Object> result = client.listTools(McpServerConfig.NO_TIMEOUT);

        assertThat(result).containsExactly("tool");
        assertThat(delegate.listToolsAttempts).isEqualTo(2);
        assertThat(delegate.lastTimeout).isEqualTo(2.5f);
    }

    @Test
    void listToolsRespectsRetryBackoffBeforeSucceeding() throws Exception {
        FlakyMcpClient delegate = new FlakyMcpClient(0);
        delegate.failListToolsAttempts = 1;
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.setTimeoutMs(2500);
        policy.getRetry().setMax(1);
        policy.getRetry().setBackoffMs(80);

        DecoratingMcpClient client = new DecoratingMcpClient(McpServerConfig.builder()
            .serverId("srv-backoff")
            .serverName("demo")
            .serverPath("http://localhost/mcp")
            .build(), delegate, policy);

        long startNanos = System.nanoTime();
        List<Object> result = client.listTools(McpServerConfig.NO_TIMEOUT);
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(result).containsExactly("tool");
        assertThat(delegate.listToolsAttempts).isEqualTo(2);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(70);
    }

    @Test
    void auditEnabledWritesSuccessfulEntryWithMarkerAndRedactedRequest(CapturedOutput output) throws Exception {
        FlakyMcpClient delegate = new FlakyMcpClient(0);
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();

        DecoratingMcpClient client = new DecoratingMcpClient(McpServerConfig.builder()
            .serverId("srv-audit")
            .serverName("demo")
            .serverPath("http://localhost/mcp")
            .build(), delegate, policy);

        assertThat(
            client.callTool("echo", Map.of("text", "hi", "secret", "token-123"), McpServerConfig.NO_TIMEOUT)).isEqualTo(
            "ok");

        assertThat(output).contains("EXTERNAL_CALL_AUDIT")
            .contains("adapter=MCP")
            .contains("success=true")
            .contains("target=srv-audit/demo")
            .contains("method=mcp.tools/call")
            .contains("request=Map(size=2")
            .doesNotContain("token-123");
    }

    @Test
    void auditEnabledWritesFailureEntryWithCodeAndMarker(CapturedOutput output) {
        FlakyMcpClient delegate = new FlakyMcpClient(Integer.MAX_VALUE);
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();

        DecoratingMcpClient client = new DecoratingMcpClient(McpServerConfig.builder()
            .serverId("srv-audit-failure")
            .serverName("demo")
            .serverPath("http://localhost/mcp")
            .build(), delegate, policy);

        assertThatThrownBy(
            () -> client.callTool("echo", Map.of("text", "hi"), McpServerConfig.NO_TIMEOUT)).isInstanceOf(
            ExternalSvcAdapterException.class);

        assertThat(output).contains("EXTERNAL_CALL_AUDIT")
            .contains("adapter=MCP")
            .contains("success=false")
            .contains("code=EXT_MCP_001")
            .contains("target=srv-audit-failure/demo")
            .contains("method=mcp.tools/call")
            .contains("error=IllegalStateException:boom");
    }

    @Test
    void auditDisabledSuppressesAuditEntries(CapturedOutput output) throws Exception {
        FlakyMcpClient delegate = new FlakyMcpClient(0);
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.getAudit().setEnabled(false);

        DecoratingMcpClient client = new DecoratingMcpClient(McpServerConfig.builder()
            .serverId("srv-audit-off")
            .serverName("demo")
            .serverPath("http://localhost/mcp")
            .build(), delegate, policy);

        assertThat(client.callTool("echo", Map.of("text", "hi"), McpServerConfig.NO_TIMEOUT)).isEqualTo("ok");

        assertThat(output).doesNotContain("EXTERNAL_CALL_AUDIT");
    }

    @Test
    void listToolsUsesHardTimeoutWhenDelegateIgnoresTimeout() {
        FlakyMcpClient delegate = new FlakyMcpClient(0);
        delegate.listToolsDelayMs = 250;
        AgentCoreExternalProperties.McpPolicy policy = new AgentCoreExternalProperties.McpPolicy();
        policy.setTimeoutMs(50);
        policy.getRetry().setMax(0);

        DecoratingMcpClient client = new DecoratingMcpClient(McpServerConfig.builder()
            .serverId("srv-timeout")
            .serverName("demo")
            .serverPath("http://localhost/mcp")
            .build(), delegate, policy);

        long startNanos = System.nanoTime();
        assertThatThrownBy(() -> client.listTools(McpServerConfig.NO_TIMEOUT)).isInstanceOf(
                ExternalSvcAdapterException.class)
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

        DecoratingMcpClient client = new DecoratingMcpClient(McpServerConfig.builder()
            .serverId("srv-unsafe")
            .serverName("demo")
            .serverPath("http://localhost/mcp")
            .build(), delegate, policy);

        assertThatThrownBy(
            () -> client.callTool("echo", Map.of("text", "hi"), McpServerConfig.NO_TIMEOUT)).isInstanceOf(
                ExternalSvcAdapterException.class)
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

        DecoratingMcpClient client = new DecoratingMcpClient(McpServerConfig.builder()
            .serverId("srv-retry-tool")
            .serverName("demo")
            .serverPath("http://localhost/mcp")
            .build(), delegate, policy);

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
            McpServerConfig.builder().serverId("srv-2").serverName("demo").serverPath("http://localhost/mcp").build(),
            delegate, policy);

        assertThatThrownBy(() -> client.callTool("echo", Map.of(), McpServerConfig.NO_TIMEOUT)).isInstanceOf(
                ExternalSvcAdapterException.class)
            .extracting("errorCode")
            .isEqualTo(ExternalSvcAdapterErrorCode.MCP_OUTBOUND_CALL_FAILED);
        assertThatThrownBy(() -> client.callTool("echo", Map.of(), McpServerConfig.NO_TIMEOUT)).isInstanceOf(
                ExternalSvcAdapterException.class)
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
            McpServerConfig.builder().serverId("srv-3").serverName("demo").serverPath("http://localhost/mcp").build(),
            delegate, policy);

        assertThatThrownBy(() -> client.callTool("echo", Map.of(), McpServerConfig.NO_TIMEOUT)).isInstanceOf(
                ExternalSvcAdapterException.class)
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
            long remainingTimeMs = delayMs;
            while (remainingTimeMs > 0) {
                try {
                    Thread.sleep(remainingTimeMs);
                } catch (InterruptedException ignored) {
                    // Keep sleeping to prove the decorator owns the timeout.
                }
                remainingTimeMs = (deadline - System.nanoTime()) / 1_000_000L;
            }
        }
    }
}