/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.spec.dto.ServeRequest;
import org.a2aproject.sdk.spec.MessageSendParams;
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
        Map<String, Object> messageMetadata = new LinkedHashMap<>(Map.of("scope", "message", "trace-id", "trace-1"));

        ServeRequest request = new ServeRequest();
        request.setMetadata(paramsMetadata);
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", "hello");
        userMessage.put("metadata", messageMetadata);
        request.setMessages(List.of(userMessage));

        RemoteAgentCall call = new RemoteAgentCall("remote", request, null, "ctx-original", "task-1");
        MessageSendParams params = A2ARemoteAgentClient.buildSendParams(call, "hello", "ctx-resolved");
        // Mutate the source maps after buildSendParams — it must have taken immutable snapshots.
        paramsMetadata.put("late", "params-change");
        messageMetadata.put("late", "message-change");

        assertThat(params.metadata()).containsExactlyEntriesOf(Map.of("scope", "params"));
        assertThat(params.message().metadata()).containsEntry("scope", "message").containsEntry("trace-id", "trace-1")
                .doesNotContainKey("late");
        assertThat(params.message().contextId()).isEqualTo("ctx-resolved");
        assertThat(params.message().taskId()).isEqualTo("task-1");
    }

    @Test
    void emptyMetadataProducesEmptyParamsAndMessageMetadata() {
        ServeRequest request = new ServeRequest();
        request.setMetadata(Map.of("scope", "params"));
        Map<String, Object> userMessage = new LinkedHashMap<>();
        userMessage.put("role", "user");
        userMessage.put("content", "hello");
        request.setMessages(List.of(userMessage));

        ServeRequest emptyRequest = new ServeRequest();

        RemoteAgentCall call = new RemoteAgentCall("remote", request, null, "ctx", null);
        RemoteAgentCall emptyCall = new RemoteAgentCall("remote", emptyRequest, null, "ctx", null);

        MessageSendParams params = A2ARemoteAgentClient.buildSendParams(call, "hello", "ctx");
        MessageSendParams emptyParams = A2ARemoteAgentClient.buildSendParams(emptyCall, "hello", "ctx");

        assertThat(params.metadata()).containsExactlyEntriesOf(Map.of("scope", "params"));
        assertThat(params.message().metadata()).isEmpty();
        assertThat(emptyParams.metadata()).isEmpty();
        assertThat(emptyParams.message().metadata()).isEmpty();
    }
}
