/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.ServeRequest;
import java.util.List;
import java.util.Map;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

/**
 * Protocol-specific behaviours that are non-trivial and must be verified: role normalisation and JSON-trial-parsing in
 * part texts.
 */
class A2AProtocolAdapterTest {

    private final A2AProtocolAdapter adapter = new A2AProtocolAdapter();

    @Test
    void normalizesProtobufRoleToInternal() {
        assertThat(A2AProtocolAdapter.normalizeRole("ROLE_USER")).isEqualTo("user");
        assertThat(A2AProtocolAdapter.normalizeRole("ROLE_AGENT")).isEqualTo("assistant");
        assertThat(A2AProtocolAdapter.normalizeRole("ROLE_SYSTEM")).isEqualTo("system");
    }

    @Test
    void unrecognisedRoleFallsBackToLowercase() {
        assertThat(A2AProtocolAdapter.normalizeRole("CUSTOM")).isEqualTo("custom");
    }

    @Test
    void mapsContextIdAndPreservesMetadata() {
        A2AMessageContext ctx = new A2AMessageContext();
        ctx.setContextId("conv-1");
        ctx.setMetadata(Map.of("key", "val"));
        ctx.setA2aMessage(
                Message.builder().role(Message.Role.ROLE_USER).parts(List.<Part<?>>of(new TextPart("hello"))).build());

        ServeRequest req = adapter.toServeRequest(ctx);

        assertThat(req.getConversationId()).isEqualTo("conv-1");
        assertThat(req.getMetadata()).containsEntry("key", "val");
        assertThat(req.getMessages().get(0)).containsEntry("role", "user");
    }
}
