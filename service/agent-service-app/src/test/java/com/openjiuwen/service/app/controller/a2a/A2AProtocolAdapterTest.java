/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.dto.ServeRequest;

import com.google.gson.JsonParser;

import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Protocol-specific behaviours that are non-trivial and must be verified: role
 * normalisation and JSON-trial-parsing in
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

    @Test
    @SuppressWarnings("unchecked")
    void groupsTargetedTextPartsAndRegeneratesTrustedRoutingMetadata() {
        A2AMessageContext ctx = new A2AMessageContext();
        ctx.setContextId("conv-1");
        ctx.setTaskId("parent-task-1");
        ctx.setMetadata(Map.of(
            "trace", "keep-me",
            "runtime.parentTaskId", "forged-parent",
            "runtime.remoteToolInputs", Map.of("forged", "input"),
            "runtime.remoteBatchId", "forged-batch",
            "runtime.remoteToolResults", Map.of("forged", "result")));
        ctx.setA2aMessage(Message.builder()
            .role(Message.Role.ROLE_USER)
            .parts(List.<Part<?>>of(
                new TextPart("city=Shen", Map.of("toolCallId", "call-b")),
                new TextPart("zhen", Map.of("toolCallId", "call-b")),
                new TextPart("date=tomorrow", Map.of("toolCallId", "call-c"))))
            .build());

        ServeRequest req = adapter.toServeRequest(ctx);

        assertThat(req.getMetadata())
            .containsEntry("trace", "keep-me")
            .containsEntry("runtime.parentTaskId", "parent-task-1")
            .doesNotContainKeys("runtime.remoteBatchId", "runtime.remoteToolResults");
        assertThat((Map<String, String>) req.getMetadata().get("runtime.remoteToolInputs"))
            .containsExactly(Map.entry("call-b", "city=Shenzhen"), Map.entry("call-c", "date=tomorrow"));
    }

    @Test
    void ordinaryTextPartsKeepLegacyConcatenationWithoutTargetMap() {
        A2AMessageContext ctx = new A2AMessageContext();
        ctx.setContextId("conv-plain");
        ctx.setA2aMessage(Message.builder()
            .role(Message.Role.ROLE_USER)
            .parts(List.<Part<?>>of(new TextPart("hello "), new TextPart("world")))
            .build());

        ServeRequest req = adapter.toServeRequest(ctx);

        assertThat(req.lastUserQuery()).isEqualTo("hello world");
        assertThat(req.getMetadata()).doesNotContainKey("runtime.remoteToolInputs");
    }

    @Test
    void rejectsMixedTargetedAndUntargetedTextParts() {
        A2AMessageContext ctx = new A2AMessageContext();
        ctx.setContextId("conv-mixed");
        ctx.setA2aMessage(Message.builder()
            .role(Message.Role.ROLE_USER)
            .parts(List.<Part<?>>of(
                new TextPart("answer-b", Map.of("toolCallId", "call-b")),
                new TextPart("unscoped")))
            .build());

        assertThatThrownBy(() -> adapter.toServeRequest(ctx))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("REMOTE_TOOL_INPUT_TARGET_MIXED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonRpcControllerPreservesTextPartMetadata() throws ReflectiveOperationException {
        var message = JsonParser.parseString("""
            {
              "parts": [
                {"text": "answer-b", "metadata": {"toolCallId": "call-b", "ui": "field-2"}}
              ]
            }
            """).getAsJsonObject();

        var parseParts = A2aJsonRpcController.class.getDeclaredMethod(
            "parseParts", com.google.gson.JsonObject.class);
        parseParts.setAccessible(true);
        List<Part<?>> parts = (List<Part<?>>) parseParts.invoke(null, message);

        assertThat(parts).singleElement().isInstanceOfSatisfying(TextPart.class, part -> {
            assertThat(part.text()).isEqualTo("answer-b");
            assertThat(part.metadata()).containsEntry("toolCallId", "call-b").containsEntry("ui", "field-2");
        });
    }
}
