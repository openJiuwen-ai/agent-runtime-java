/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.dto.ServeRequest;

import com.google.gson.JsonParser;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
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
        ctx.setMetadata(Map.of("scope", "params"));
        ctx.setHeaders(Map.of("x-user-id", "trusted-user", "x-space-id", "trusted-space"));
        ctx.setA2aMessage(Message.builder().role(Message.Role.ROLE_USER).parts(List.<Part<?>>of(new TextPart("hello")))
                .metadata(Map.of("scope", "message", "userId", "untrusted-user")).build());

        ServeRequest req = adapter.toServeRequest(ctx);

        assertThat(req.getConversationId()).isEqualTo("conv-1");
        assertThat(req.getMetadata()).containsExactlyEntriesOf(Map.of("scope", "params"));
        assertThat(req.lastUserMessageMetadata()).containsEntry("scope", "message").containsEntry("userId",
                "untrusted-user");
        assertThat(req.getUserId()).isEqualTo("trusted-user");
        assertThat(req.getSpaceId()).isEqualTo("trusted-space");
        assertThat(req.getMessages().get(0)).containsEntry("role", "user");
    }

    @Test
    @SuppressWarnings("unchecked")
    void groupsTargetedTextPartsAndRegeneratesTrustedRoutingMetadata() {
        A2AMessageContext ctx = new A2AMessageContext();
        ctx.setContextId("conv-1");
        ctx.setTaskId("parent-task-1");
        ctx.setMetadata(Map.of("trace", "keep-me", "runtime.parentTaskId", "forged-parent", "runtime.remoteToolInputs",
                Map.of("forged", "input"), "runtime.remoteBatchId", "forged-batch", "runtime.remoteToolResults",
                Map.of("forged", "result")));
        ctx.setA2aMessage(Message.builder().role(Message.Role.ROLE_USER)
                .parts(List.<Part<?>>of(new TextPart("city=Shen", Map.of("toolCallId", "call-b")),
                        new TextPart("zhen", Map.of("toolCallId", "call-b")),
                        new TextPart("date=tomorrow", Map.of("toolCallId", "call-c"))))
                .build());

        ServeRequest req = adapter.toServeRequest(ctx);

        assertThat(req.getMetadata()).containsEntry("trace", "keep-me")
                .containsEntry("runtime.parentTaskId", "parent-task-1")
                .doesNotContainKeys("runtime.remoteBatchId", "runtime.remoteToolResults");
        assertThat((Map<String, String>) req.getMetadata().get("runtime.remoteToolInputs"))
                .containsExactly(Map.entry("call-b", "city=Shenzhen"), Map.entry("call-c", "date=tomorrow"));
    }

    @Test
    void ordinaryTextPartsKeepLegacyConcatenationWithoutTargetMap() {
        A2AMessageContext ctx = new A2AMessageContext();
        ctx.setContextId("conv-plain");
        ctx.setA2aMessage(Message.builder().role(Message.Role.ROLE_USER)
                .parts(List.<Part<?>>of(new TextPart("hello "), new TextPart("world"))).build());

        ServeRequest req = adapter.toServeRequest(ctx);

        assertThat(req.lastUserQuery()).isEqualTo("hello world");
        assertThat(req.getMetadata()).doesNotContainKey("runtime.remoteToolInputs");
    }

    @Test
    void rejectsMixedTargetedAndUntargetedTextParts() {
        A2AMessageContext ctx = new A2AMessageContext();
        ctx.setContextId("conv-mixed");
        ctx.setA2aMessage(Message.builder().role(Message.Role.ROLE_USER).parts(
                List.<Part<?>>of(new TextPart("answer-b", Map.of("toolCallId", "call-b")), new TextPart("unscoped")))
                .build());

        assertThatThrownBy(() -> adapter.toServeRequest(ctx)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("REMOTE_TOOL_INPUT_TARGET_MIXED");
    }

    @Test
    void jsonRpcControllerPreservesTextPartMetadata() {
        var request = JsonParser.parseString("""
                {
                  "params": {
                    "message": {
                      "role": "ROLE_USER",
                      "parts": [
                        {"text": "answer-b", "metadata": {"toolCallId": "call-b", "ui": "field-2"}}
                      ]
                    }
                  }
                }
                """).getAsJsonObject();

        List<Part<?>> parts = A2aJsonRpcParamsParser.parseMessageSendParams(request).message().parts();

        assertThat(parts).singleElement().isInstanceOfSatisfying(TextPart.class, part -> {
            assertThat(part.text()).isEqualTo("answer-b");
            assertThat(part.metadata()).containsEntry("toolCallId", "call-b").containsEntry("ui", "field-2");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void toServeRequestWritesNormalizedPartsInOrder() {
        A2AMessageContext ctx = new A2AMessageContext();
        ctx.setContextId("conv-parts");
        ctx.setA2aMessage(
                Message.builder().role(Message.Role.ROLE_USER)
                        .parts(List.<Part<?>>of(new TextPart("analyze this"),
                                new FilePart(new FileWithUri("application/pdf", "report.pdf",
                                        "https://example.com/report.pdf")),
                                new DataPart(Map.of("key", "value"))))
                        .build());

        ServeRequest req = adapter.toServeRequest(ctx);

        Map<String, Object> userMsg = req.getMessages().get(0);
        assertThat(userMsg.get("content")).isEqualTo("analyze this");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) userMsg.get("parts");
        assertThat(parts).hasSize(3);
        assertThat(parts.get(0)).containsEntry("kind", "text").containsEntry("text", "analyze this");
        assertThat(parts.get(1)).containsEntry("kind", "url").containsEntry("url", "https://example.com/report.pdf")
                .containsEntry("filename", "report.pdf").containsEntry("mediaType", "application/pdf");
        assertThat(parts.get(2)).containsEntry("kind", "data").containsEntry("data", Map.of("key", "value"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void toServeRequestNormalizesRawPartWithBytesAndSize() {
        String base64 = java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2, 3});
        A2AMessageContext ctx = new A2AMessageContext();
        ctx.setContextId("conv-raw");
        ctx.setA2aMessage(Message.builder().role(Message.Role.ROLE_USER)
                .parts(List.<Part<?>>of(new FilePart(new FileWithBytes("application/octet-stream", "a.bin", base64))))
                .build());

        ServeRequest req = adapter.toServeRequest(ctx);

        List<Map<String, Object>> parts = (List<Map<String, Object>>) req.getMessages().get(0).get("parts");
        assertThat(parts).singleElement().satisfies(part -> {
            assertThat(part).containsEntry("kind", "raw");
            assertThat(part).containsEntry("bytesBase64", base64);
            assertThat(part).containsEntry("byteSize", 3L);
            assertThat(part).containsEntry("filename", "a.bin");
            assertThat(part).containsEntry("mediaType", "application/octet-stream");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void toServeRequestIncludesTextPartsAndKeepsLegacyContent() {
        A2AMessageContext ctx = new A2AMessageContext();
        ctx.setContextId("conv-text");
        ctx.setA2aMessage(Message.builder().role(Message.Role.ROLE_USER)
                .parts(List.<Part<?>>of(new TextPart("hello "), new TextPart("world"))).build());

        ServeRequest req = adapter.toServeRequest(ctx);

        assertThat(req.lastUserQuery()).isEqualTo("hello world");
        List<Map<String, Object>> parts = (List<Map<String, Object>>) req.getMessages().get(0).get("parts");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).containsEntry("kind", "text").containsEntry("text", "hello ");
        assertThat(parts.get(1)).containsEntry("kind", "text").containsEntry("text", "world");
    }
}
