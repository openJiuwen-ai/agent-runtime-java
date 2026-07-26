/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

/**
 * Verifies that inline callback configuration is bound to the task created by the real A2A entrypoint.
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "openjiuwen.service.a2a.push-notifications=true"
    })
@AutoConfigureTestRestTemplate
class TaskStoreCallbackBindingTest {
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PushNotificationConfigStore pushConfigStore;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void sendMessageBindsInlinePushConfigToCreatedTaskId() throws Exception {
        String callbackUrl = "http://127.0.0.1/a2a/push-notifications/callback";
        Map<String, Object> request = Map.of(
            "jsonrpc", "2.0",
            "id", "callback-binding",
            "method", "SendMessage",
            "params", Map.of(
                "message", Map.of(
                    "role", "ROLE_USER",
                    "messageId", "msg-callback-binding",
                    "contextId", "ctx-callback-binding",
                    "parts", List.of(Map.of("kind", "text", "text", "bind callback config"))),
                "pushNotificationConfig", Map.of(
                    "id", "push-callback-binding",
                    "callbackUrl", callbackUrl,
                    "token", "token-ref")));

        Map<String, Object> body = json(postA2a(request));
        Map<String, Object> task = (Map<String, Object>) ((Map<String, Object>) body.get("result")).get("task");
        String taskId = String.valueOf(task.get("id"));

        assertThat(taskId).isNotBlank();
        assertThat(pushConfigStore.getInfo(new ListTaskPushNotificationConfigsParams(taskId)).configs())
            .singleElement()
            .satisfies(config -> assertThat(config)
                .returns("push-callback-binding", TaskPushNotificationConfig::id)
                .returns(taskId, TaskPushNotificationConfig::taskId)
                .returns(callbackUrl, TaskPushNotificationConfig::url)
                .returns("token-ref", TaskPushNotificationConfig::token));
    }

    private String postA2a(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForObject("/a2a/", new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String body) throws Exception {
        return mapper.readValue(body, Map.class);
    }
}
