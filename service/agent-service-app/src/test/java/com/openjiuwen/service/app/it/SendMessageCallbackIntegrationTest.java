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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

/**
 * Integration journey for SendMessage with inline push callback configuration.
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "openjiuwen.service.a2a.push-notifications=true",
        "openjiuwen.service.a2a.push-notification.trusted-callback-hosts[0]=127.0.0.1"
    })
@AutoConfigureTestRestTemplate
class SendMessageCallbackIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private PushNotificationConfigStore pushConfigStore;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @SuppressWarnings("unchecked")
    void sendMessageWithInlineCallbackConfigCreatesQueryableTaskAndBindsCallbackConfig() throws Exception {
        String callbackUrl = "http://127.0.0.1/a2a/push-notifications/callback";
        Map<String, Object> request = rpc("SendMessage", "send-callback-journey", Map.of(
            "message", Map.of(
                "role", "ROLE_USER",
                "messageId", "msg-send-callback-journey",
                "contextId", "ctx-send-callback-journey",
                "parts", List.of(Map.of("kind", "text", "text", "send callback journey"))),
            "pushNotificationConfig", Map.of(
                "id", "push-send-callback-journey",
                "callbackUrl", callbackUrl,
                "token", "token-ref")));

        Map<String, Object> sendBody = json(postA2a(request));
        Map<String, Object> task = taskFrom(sendBody);
        String taskId = String.valueOf(task.get("id"));

        assertThat(sendBody).containsEntry("jsonrpc", "2.0").containsEntry("id", "send-callback-journey");
        assertThat(taskId).isNotBlank();
        assertThat(task.get("contextId")).isEqualTo("ctx-send-callback-journey");
        assertThat(((Map<String, Object>) task.get("status")).get("state"))
            .isIn("TASK_STATE_WORKING", "TASK_STATE_COMPLETED");
        assertBoundConfig(taskId, callbackUrl);

        Map<String, Object> getBody = awaitTaskCompleted(taskId);
        Map<String, Object> storedTask = taskFrom(getBody);

        assertThat(getBody).containsEntry("jsonrpc", "2.0");
        assertThat(storedTask.get("id")).isEqualTo(taskId);
        assertThat(storedTask.get("contextId")).isEqualTo("ctx-send-callback-journey");
        assertThat(firstArtifactText(storedTask)).contains("turn1:send callback journey");
    }

    private void assertBoundConfig(String taskId, String callbackUrl) {
        assertThat(pushConfigStore.getInfo(new ListTaskPushNotificationConfigsParams(taskId)).configs())
            .singleElement()
            .satisfies(config -> assertThat(config)
                .returns("push-send-callback-journey", TaskPushNotificationConfig::id)
                .returns(taskId, TaskPushNotificationConfig::taskId)
                .returns(callbackUrl, TaskPushNotificationConfig::url)
                .returns("token-ref", TaskPushNotificationConfig::token));
    }

    private ResponseEntity<String> postA2a(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/a2a/", new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> awaitTaskCompleted(String taskId) throws Exception {
        AssertionError last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            Map<String, Object> body = json(postA2a(rpc("GetTask", "get-callback-journey-" + attempt,
                Map.of("id", taskId))));
            Map<String, Object> task = taskFrom(body);
            Object state = ((Map<String, Object>) task.get("status")).get("state");
            if ("TASK_STATE_COMPLETED".equals(state)) {
                return body;
            }
            last = new AssertionError("task was not completed, state=" + state);
            Thread.sleep(100);
        }
        throw last;
    }

    private static Map<String, Object> rpc(String method, Object id, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return mapper.readValue(response.getBody(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> taskFrom(Map<String, Object> response) {
        Map<String, Object> result = (Map<String, Object>) response.get("result");
        if (result.containsKey("task")) {
            return (Map<String, Object>) result.get("task");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static String firstArtifactText(Map<String, Object> task) {
        var artifacts = (List<Map<String, Object>>) task.get("artifacts");
        if (artifacts == null || artifacts.isEmpty()) {
            return "";
        }
        var parts = (List<Map<String, Object>>) artifacts.get(0).get("parts");
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        Object text = parts.get(0).get("text");
        return text instanceof String value ? value : "";
    }
}
