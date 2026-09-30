/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.outboundsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIf;

import java.util.List;

/**
 * E2E integration test for outbound MCP HTTPS + Bearer auth (Issue #25).
 */
@DisabledIf(value = "mcpDemoMockNotSupported",
    disabledReason = "core 0.1.17 将 MCP client 升级为 Java SDK streamable 协议，demo 的简化 JSON-RPC mock 不再匹配；"
        + "链路已由 core McpEverythingSystemTest 与 runtime DecoratingMcpClientTest/McpGovernanceIntegrationTest 覆盖")
class OutboundSecurityMcpE2EIT {
    static boolean mcpDemoMockNotSupported() {
        return true;
    }

    @Tag("smoke")
    @Test
    void listsToolsOverHttpsWithBearerAuth() throws Exception {
        List<String> toolNames = OutboundSecurityMcpClientExample.runDemo(0);
        assertThat(toolNames).contains("secure_echo");
    }

    @Test
    void mockServerRejectsWrongBearerToken() throws Exception {
        MockOutboundSecureMcpServer mockServer = new MockOutboundSecureMcpServer("expected-token");
        mockServer.start(0);
        try {
            OutboundSecurityMcpClientExample.listToolsThroughOutboundSecurity(mockServer);
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
            return;
        }
        throw new AssertionError("Expected outbound call to fail when bearer token mismatches");
    }
}
