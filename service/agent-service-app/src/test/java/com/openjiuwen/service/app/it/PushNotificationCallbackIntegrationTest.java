/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.openjiuwen.service.app.controller.a2a.A2aPushNotificationCallbackHandler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

/**
 * Integration journey for the fixed A2A push notification callback receiver.
 */
@SpringBootTest(classes = {TestServiceApplication.class, PushNotificationCallbackIntegrationTest.CallbackTestConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
            "openjiuwen.service.a2a.push-notifications=true",
            "openjiuwen.service.a2a.push-notification.trusted-callback-hosts[0]=127.0.0.1"
        })
@AutoConfigureTestRestTemplate
class PushNotificationCallbackIntegrationTest {
    private static final String CALLBACK_PATH = "/a2a/push-notifications/callback";

    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsCallbackWithHeaderNotificationIdAndJsonRpcTaskResult() throws Exception {
        ResponseEntity<String> response = postCallback("notif-integration-accept",
                callbackBody(null, "remote-task-accept"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(response.getBody()))
            .containsEntry("status", "accepted")
            .containsEntry("notificationId", "notif-integration-accept");
    }

    @Test
    void duplicateCallbackWithSamePayloadIsIdempotentlyAccepted() throws Exception {
        String body = callbackBody("notif-integration-duplicate", "remote-task-duplicate");

        ResponseEntity<String> first = postCallback("notif-integration-duplicate", body);
        ResponseEntity<String> second = postCallback("notif-integration-duplicate", body);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json(second.getBody()))
            .containsEntry("status", "accepted")
            .containsEntry("notificationId", "notif-integration-duplicate");
    }

    @Test
    void duplicateCallbackWithDifferentPayloadReturnsConflict() throws Exception {
        postCallback("notif-integration-conflict", callbackBody("notif-integration-conflict", "remote-task-a"));

        ResponseEntity<String> conflict = postCallback("notif-integration-conflict",
                callbackBody("notif-integration-conflict", "remote-task-b"));

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(conflict.getBody()))
            .containsEntry("error", "conflict")
            .containsEntry("notificationId", "notif-integration-conflict");
    }

    @Test
    void missingNotificationIdReturnsBadRequest() throws Exception {
        ResponseEntity<String> response = postCallback(null, callbackBody(null, "remote-task-missing"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(json(response.getBody()).get("error"))).contains("notificationId");
    }

    @Test
    void mismatchedHeaderAndBodyNotificationIdReturnsBadRequest() throws Exception {
        ResponseEntity<String> response = postCallback("notif-integration-header",
                callbackBody("notif-integration-body", "remote-task-mismatch"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(json(response.getBody()).get("error"))).contains("mismatch");
    }

    @Test
    void callbackWithoutResultTaskReturnsBadRequest() throws Exception {
        ResponseEntity<String> response = postCallback("notif-integration-no-task", """
                {
                  "jsonrpc": "2.0",
                  "result": {},
                  "notificationId": "notif-integration-no-task"
                }
                """);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(String.valueOf(json(response.getBody()).get("error"))).contains("result.task");
    }

    private ResponseEntity<String> postCallback(String notificationId, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (notificationId != null) {
            headers.set("X-A2A-Notification-Id", notificationId);
        }
        headers.setBearerAuth("token-ref");
        return rest.postForEntity(CALLBACK_PATH, new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> json(String body) throws Exception {
        return mapper.readValue(body, Map.class);
    }

    private static String callbackBody(String notificationId, String taskId) {
        String notification = notificationId == null ? "" : """
                ,"notificationId":"%s"
                """.formatted(notificationId);
        return """
                {
                  "jsonrpc": "2.0",
                  "result": {
                    "task": {
                      "id": "%s",
                      "contextId": "remote-context",
                      "status": {"state": "TASK_STATE_COMPLETED"}
                    }
                  }%s
                }
                """.formatted(taskId, notification);
    }

    @TestConfiguration
    static class CallbackTestConfig {
        @Bean
        @Primary
        A2aPushNotificationCallbackHandler callbackHandler() {
            return callback -> true;
        }
    }
}
