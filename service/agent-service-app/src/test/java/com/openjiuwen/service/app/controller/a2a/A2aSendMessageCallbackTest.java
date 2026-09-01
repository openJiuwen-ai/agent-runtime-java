/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.junit.jupiter.api.Test;

/**
 * Tests callback-mode SendMessage behavior at the A2A boundary.
 */
class A2aSendMessageCallbackTest {
    @Test
    void inlinePushConfigRequestsSdkAcceptedMode() {
        MessageSendParams params = A2aJsonRpcParamsParser.parseMessageSendParams(callbackRequest());

        assertThat(params.configuration()).isNotNull();
        assertThat(params.configuration().returnImmediately()).isTrue();
        assertThat(params.configuration().taskPushNotificationConfig()).satisfies(config -> {
            assertThat(config.id()).isEqualTo("push-1");
            assertThat(config.taskId()).isNull();
            assertThat(config.url()).isEqualTo("https://caller.example/a2a/push/callback");
        });
    }

    @Test
    void inlinePushConfigCanBeBoundToResolvedTaskAndQueriedFromSdkStore() {
        MessageSendParams params = A2aJsonRpcParamsParser.parseMessageSendParams(callbackRequest());
        InMemoryPushNotificationConfigStore store = new InMemoryPushNotificationConfigStore();

        TaskPushNotificationConfig stored = store.setInfo(TaskPushNotificationConfig
                .builder(params.configuration().taskPushNotificationConfig()).taskId("task-1").build());

        assertThat(stored.taskId()).isEqualTo("task-1");
        assertThat(store.getInfo(new ListTaskPushNotificationConfigsParams("task-1")).configs())
                .singleElement().satisfies(config -> {
                    assertThat(config.id()).isEqualTo("push-1");
                    assertThat(config.taskId()).isEqualTo("task-1");
                    assertThat(config.url()).isEqualTo("https://caller.example/a2a/push/callback");
                });
    }

    private static JsonObject callbackRequest() {
        return JsonParser.parseString("""
                {
                  "params": {
                    "message": {
                      "role": "ROLE_USER",
                      "messageId": "msg-1",
                      "contextId": "ctx-1",
                      "parts": [{"kind": "text", "text": "start callback task"}]
                    },
                    "pushNotificationConfig": {
                      "id": "push-1",
                      "callbackUrl": "https://caller.example/a2a/push/callback"
                    }
                  }
                }
                """).getAsJsonObject();
    }
}
