/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.a2aproject.sdk.spec.MessageSendParams;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
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
        Map<String, Object> messageMetadata = new LinkedHashMap<>(Map.of("scope", "message", "trace-id", "trace-1"));
        var call = new RemoteCall("remote", "hello", "ctx-original", "task-1", paramsMetadata,
                messageMetadata);
        paramsMetadata.put("late", "params-change");
        messageMetadata.put("late", "message-change");

        MessageSendParams params = A2ARemoteAgentClient.buildSendParams(call, "ctx-resolved");

        assertThat(params.metadata()).containsExactlyEntriesOf(Map.of("scope", "params"));
        assertThat(params.message().metadata()).containsEntry("scope", "message").containsEntry("trace-id", "trace-1")
                .doesNotContainKey("late");
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
        var call = new A2ARemoteAgentClient.RemoteCall("remote", "hello", "ctx", null, Map.of(
            "scope", "params",
            A2ARemoteAgentClient.CALLBACK_URL_METADATA, "http://127.0.0.1:18080/a2a/push-notifications/callback",
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
}
