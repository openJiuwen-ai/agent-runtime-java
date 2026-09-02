/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.FileWithBytes;
import org.a2aproject.sdk.spec.FileWithUri;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests remote A2A request construction and metadata-level isolation.
 *
 * @since 0.1.0
 */
class A2ARemoteAgentClientMetadataTest {
        @Test
        void buildsIndependentParamsAndMessageMetadata() {
                Map<String, Object> paramsMetadata = new LinkedHashMap<>(Map.of("scope", "params"));
                Map<String, Object> messageMetadata = new LinkedHashMap<>(
                                Map.of("scope", "message", "trace-id", "trace-1"));
                var call = new RemoteCall("remote", "hello", "ctx-original", "task-1", paramsMetadata, messageMetadata);
                paramsMetadata.put("late", "params-change");
                messageMetadata.put("late", "message-change");

                MessageSendParams params = A2ARemoteAgentClient.buildSendParams(call, "ctx-resolved");

                assertThat(params.metadata()).containsExactlyEntriesOf(Map.of("scope", "params"));
                assertThat(params.message().metadata()).containsEntry("scope", "message")
                                .containsEntry("trace-id", "trace-1").doesNotContainKey("late");
                assertThat(params.message().contextId()).isEqualTo("ctx-resolved");
                assertThat(params.message().taskId()).isEqualTo("task-1");
        }

        @Test
        void compatibilityCallDoesNotPromoteParamsMetadataToMessage() {
                var call = new RemoteCall("remote", "hello", "ctx", null, Map.of("scope", "params"));
                var callWithoutMetadata = new RemoteCall("remote", "hello", "ctx", null, null);

                MessageSendParams params = A2ARemoteAgentClient.buildSendParams(call, "ctx");

                assertThat(params.metadata()).containsExactlyEntriesOf(Map.of("scope", "params"));
                assertThat(params.message().metadata()).isEmpty();
                assertThat(callWithoutMetadata.metadata()).isEmpty();
                assertThat(callWithoutMetadata.messageMetadata()).isEmpty();
                assertThat(call.isCallerStreaming()).isFalse();
                assertThat(callWithoutMetadata.isCallerStreaming()).isFalse();
        }

        @Test
        void callbackMetadataBuildsPushNotificationConfigAndStaysLocal() {
                var call = new RemoteCall("remote", "hello", "ctx", null,
                                Map.of("scope", "params", A2ARemoteAgentClient.CALLBACK_URL_METADATA,
                                                "http://127.0.0.1:18080/a2a/push-notifications/callback",
                                                A2ARemoteAgentClient.CALLBACK_TOKEN_METADATA, "secret",
                                                A2ARemoteAgentClient.CALLBACK_ID_METADATA, "push-ctx"));

                MessageSendParams params = A2ARemoteAgentClient.buildSendParams(call, "ctx");

                assertThat(params.metadata()).containsExactlyEntriesOf(Map.of("scope", "params"));
                assertThat(params.configuration().returnImmediately()).isTrue();
                assertThat(params.configuration().taskPushNotificationConfig()).satisfies(config -> assertThat(config)
                                .returns("push-ctx", org.a2aproject.sdk.spec.TaskPushNotificationConfig::id)
                                .returns("http://127.0.0.1:18080/a2a/push-notifications/callback",
                                                org.a2aproject.sdk.spec.TaskPushNotificationConfig::url)
                                .returns("secret", org.a2aproject.sdk.spec.TaskPushNotificationConfig::token));
        }

        @Test
        void buildSendParamsAppendsNormalizedPartsAfterLeadingTextPart() {
                var call = new RemoteCall("remote", "analyze", "ctx", null, Map.of(), Map.of(), false,
                                List.of(Map.of("kind", "url", "url", "https://example.com/report.pdf", "filename",
                                                "report.pdf", "mediaType", "application/pdf"),
                                                Map.of("kind", "raw", "bytesBase64", "aGVsbG8=", "byteSize", 5,
                                                                "filename", "doc.txt", "mediaType", "text/plain"),
                                                Map.of("kind", "data", "data", Map.of("amount", 100)),
                                                Map.of("kind", "text", "text", "extra context")));

                MessageSendParams params = A2ARemoteAgentClient.buildSendParams(call, "ctx");

                List<Part<?>> parts = params.message().parts();
                assertThat(parts).hasSize(5);
                assertThat(parts.get(0)).isInstanceOf(TextPart.class);
                assertThat(assertInstanceOf(TextPart.class, parts.get(0)).text()).isEqualTo("analyze");
                assertThat(parts.get(1)).isInstanceOf(FilePart.class);
                assertThat(assertInstanceOf(FilePart.class, parts.get(1)).file())
                                .isInstanceOfSatisfying(FileWithUri.class, uri -> {
                                        assertThat(uri.uri()).isEqualTo("https://example.com/report.pdf");
                                        assertThat(uri.name()).isEqualTo("report.pdf");
                                        assertThat(uri.mimeType()).isEqualTo("application/pdf");
                                });
                assertThat(parts.get(2)).isInstanceOf(FilePart.class);
                assertThat(assertInstanceOf(FilePart.class, parts.get(2)).file())
                                .isInstanceOfSatisfying(FileWithBytes.class, bytes -> {
                                        assertThat(bytes.bytes()).isEqualTo("aGVsbG8=");
                                        assertThat(bytes.name()).isEqualTo("doc.txt");
                                        assertThat(bytes.mimeType()).isEqualTo("text/plain");
                                });
                assertThat(parts.get(3)).isInstanceOf(DataPart.class);
                assertThat(assertInstanceOf(DataPart.class, parts.get(3)).data()).isEqualTo(Map.of("amount", 100));
                assertThat(parts.get(4)).isInstanceOf(TextPart.class);
                assertThat(assertInstanceOf(TextPart.class, parts.get(4)).text()).isEqualTo("extra context");
        }

        @Test
        void buildSendParamsKeepsUrlFormatAndMovesExtraTextAfterFileParts() {
                var call = new RemoteCall("remote", "analyze", "ctx", null, Map.of(), Map.of(), false,
                                List.of(Map.of("kind", "text", "text", "preface"), Map.of("kind", "url", "url",
                                                "https://example.com/report.pdf", "mediaType", "application/pdf")));

                MessageSendParams params = A2ARemoteAgentClient.buildSendParams(call, "ctx");

                List<Part<?>> parts = params.message().parts();
                assertThat(parts).hasSize(3);
                assertThat(parts.get(1)).isInstanceOf(FilePart.class);
                assertThat(assertInstanceOf(FilePart.class, parts.get(1)).file()).isInstanceOf(FileWithUri.class);
                assertThat(parts.get(2)).isInstanceOf(TextPart.class);
                assertThat(assertInstanceOf(TextPart.class, parts.get(2)).text()).isEqualTo("preface");
        }

        @Test
        void buildSendParamsWithoutPartsKeepsLegacySingleTextPart() {
                var call = new RemoteCall("remote", "hello", "ctx", null, Map.of(), Map.of(), false, null);

                MessageSendParams params = A2ARemoteAgentClient.buildSendParams(call, "ctx");

                assertThat(params.message().parts()).singleElement().isInstanceOf(TextPart.class);
        }

        @Test
        void outboundPartsSerializeToFlatV100WireFormat() {
                var call = new RemoteCall("remote", "analyze", "ctx", null, Map.of(), Map.of(), false, List.of(
                                Map.of("kind", "url", "url", "https://example.com/chart.png", "filename", "chart.png",
                                                "mediaType", "image/png"),
                                Map.of("kind", "raw", "bytesBase64", "aGVsbG8=", "byteSize", 5, "filename", "notes.txt",
                                                "mediaType", "text/plain"),
                                Map.of("kind", "data", "data", Map.of("orderId", "A-1024", "vip", true))));

                MessageSendParams params = A2ARemoteAgentClient.buildSendParams(call, "ctx");

                String wire = org.a2aproject.sdk.grpc.utils.JSONRPCUtils.toJsonRPCRequest("req-1",
                                org.a2aproject.sdk.spec.A2AMethods.SEND_MESSAGE_METHOD,
                                org.a2aproject.sdk.grpc.utils.ProtoUtils.ToProto.sendMessageRequest(params));

                com.google.gson.JsonObject body = com.google.gson.JsonParser.parseString(wire).getAsJsonObject();
                assertThat(body.get("method").getAsString()).isEqualTo("SendMessage");
                com.google.gson.JsonObject message = body.getAsJsonObject("params").getAsJsonObject("message");
                com.google.gson.JsonArray parts = message.getAsJsonArray("parts");

                assertThat(message.get("role").getAsString()).isEqualTo("ROLE_USER");
                assertThat(parts).hasSize(4);
                for (com.google.gson.JsonElement part : parts) {
                        assertThat(part.getAsJsonObject().keySet()).doesNotContain("kind", "file");
                }
                assertThat(parts.get(0).getAsJsonObject().get("text").getAsString()).isEqualTo("analyze");
                com.google.gson.JsonObject urlPart = parts.get(1).getAsJsonObject();
                assertThat(urlPart.get("url").getAsString()).isEqualTo("https://example.com/chart.png");
                assertThat(urlPart.get("filename").getAsString()).isEqualTo("chart.png");
                assertThat(urlPart.get("mediaType").getAsString()).isEqualTo("image/png");
                com.google.gson.JsonObject rawPart = parts.get(2).getAsJsonObject();
                assertThat(rawPart.get("raw").getAsString()).isEqualTo("aGVsbG8=");
                assertThat(rawPart.get("filename").getAsString()).isEqualTo("notes.txt");
                assertThat(rawPart.get("mediaType").getAsString()).isEqualTo("text/plain");
                com.google.gson.JsonObject dataPart = parts.get(3).getAsJsonObject();
                assertThat(dataPart.getAsJsonObject("data").get("orderId").getAsString()).isEqualTo("A-1024");
                assertThat(dataPart.getAsJsonObject("data").get("vip").getAsBoolean()).isTrue();
        }
}
