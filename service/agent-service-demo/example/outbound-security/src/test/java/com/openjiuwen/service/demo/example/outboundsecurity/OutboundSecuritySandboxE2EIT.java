/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.outboundsecurity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * E2E integration test for outbound Sandbox HTTPS + Bearer auth (Issue #25).
 */
class OutboundSecuritySandboxE2EIT {
    @Test
    void readsFileOverHttpsWithBearerAuth() throws Exception {
        String content = OutboundSecuritySandboxClientExample.runDemo(0);
        assertThat(content).contains("secure jiuwenbox file:/tmp/demo.txt");
    }

    @Test
    void mockServerRejectsWrongBearerToken() throws Exception {
        MockOutboundSecureJiuwenBoxServer mockServer = new MockOutboundSecureJiuwenBoxServer("expected-token");
        mockServer.start(0);
        try {
            OutboundSecuritySandboxClientExample.readFileThroughOutboundSecurity(mockServer);
        } catch (Exception ex) {
            assertThat(ex).isNotNull();
            return;
        }
        throw new AssertionError("Expected outbound sandbox call to fail when bearer token mismatches");
    }
}
