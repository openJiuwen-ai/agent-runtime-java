/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openjiuwen.service.app.config.A2AProperties;

import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests fixed A2A push notification callback receiver behavior.
 */
class A2aPushNotificationCallbackControllerTest {
    @Test
    void acceptsCallbackWithNotificationHeaderAndJsonRpcResultBody() {
        A2aPushNotificationCallbackController controller = controller();
        MockHttpServletRequest request = request("notif-1");

        ResponseEntity<String> response = controller.handleCallback(callbackBody(null, "task-1"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
        assertThat(body.get("status").getAsString()).isEqualTo("accepted");
        assertThat(body.get("notificationId").getAsString()).isEqualTo("notif-1");
    }

    @Test
    void duplicateCallbackWithSamePayloadIsIdempotentlyAccepted() {
        A2aPushNotificationCallbackController controller = controller();
        MockHttpServletRequest request = request("notif-1");
        String body = callbackBody("notif-1", "task-1");

        ResponseEntity<String> first = controller.handleCallback(body, request);
        ResponseEntity<String> second = controller.handleCallback(body, request);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(JsonParser.parseString(second.getBody()).getAsJsonObject().get("notificationId").getAsString())
                .isEqualTo("notif-1");
    }

    @Test
    void firstAcceptedCallbackIsHandedToRecoveryHandlerOnce() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> remoteTaskId = new AtomicReference<>();
        A2aPushNotificationCallbackHandler handler = callback -> {
            calls.incrementAndGet();
            remoteTaskId.set(callback.task().id());
            return true;
        };
        A2aPushNotificationCallbackController controller = new A2aPushNotificationCallbackController(
                new InMemoryA2aPushNotificationCallbackStore(), handler, enabledGate(handler));
        MockHttpServletRequest request = request("notif-1");
        String body = callbackBody("notif-1", "task-1");

        controller.handleCallback(body, request);
        controller.handleCallback(body, request);

        assertThat(calls).hasValue(1);
        assertThat(remoteTaskId).hasValue("task-1");
    }

    @Test
    void disabledCapabilityGateRejectsBeforeStoreOrHandler() {
        AtomicInteger storeCalls = new AtomicInteger();
        AtomicInteger handlerCalls = new AtomicInteger();
        A2aPushNotificationCallbackStore store = (notificationId, payloadHash) -> {
            storeCalls.incrementAndGet();
            return A2aPushNotificationCallbackStore.SaveResult.CREATED;
        };
        A2aPushNotificationCallbackController controller = new A2aPushNotificationCallbackController(store,
                callback -> {
                    handlerCalls.incrementAndGet();
                    return true;
                }, disabledGate());

        ResponseEntity<String> response = controller.handleCallback(callbackBody("notif-1", "task-1"),
                request("notif-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(storeCalls).hasValue(0);
        assertThat(handlerCalls).hasValue(0);
    }

    @Test
    void bindingNotFoundReturnsNotFound() {
        A2aPushNotificationCallbackHandler handler = callback -> false;
        A2aPushNotificationCallbackController controller = new A2aPushNotificationCallbackController(
                new InMemoryA2aPushNotificationCallbackStore(), handler, enabledGate(handler));

        ResponseEntity<String> response = controller.handleCallback(callbackBody("notif-1", "task-1"),
                request("notif-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        JsonObject body = JsonParser.parseString(response.getBody()).getAsJsonObject();
        assertThat(body.get("error").getAsString()).contains("binding not found");
        assertThat(body.get("notificationId").getAsString()).isEqualTo("notif-1");
    }

    @Test
    void rejectsCallbackWithoutResultTask() {
        String body = """
                {
                  "jsonrpc": "2.0",
                  "result": {},
                  "notificationId": "notif-1"
                }
                """;

        ResponseEntity<String> response = controller().handleCallback(body, request("notif-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonParser.parseString(response.getBody()).getAsJsonObject().get("error").getAsString())
                .contains("result.task");
    }

    @Test
    void duplicateCallbackWithDifferentPayloadReturnsConflict() {
        A2aPushNotificationCallbackController controller = controller();
        MockHttpServletRequest request = request("notif-1");

        controller.handleCallback(callbackBody("notif-1", "task-1"), request);
        ResponseEntity<String> conflict = controller.handleCallback(callbackBody("notif-1", "task-2"), request);

        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonObject body = JsonParser.parseString(conflict.getBody()).getAsJsonObject();
        assertThat(body.get("error").getAsString()).contains("conflict");
    }

    @Test
    void rejectsMissingNotificationId() {
        ResponseEntity<String> response = controller().handleCallback(callbackBody(null, "task-1"),
                new MockHttpServletRequest("POST", "/a2a/push-notifications/callback"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonParser.parseString(response.getBody()).getAsJsonObject().get("error").getAsString())
                .contains("notificationId");
    }

    @Test
    void rejectsMismatchedHeaderAndBodyNotificationId() {
        ResponseEntity<String> response = controller().handleCallback(callbackBody("body-id", "task-1"),
                request("header-id"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(JsonParser.parseString(response.getBody()).getAsJsonObject().get("error").getAsString())
                .contains("mismatch");
    }

    private static A2aPushNotificationCallbackController controller() {
        A2aPushNotificationCallbackHandler handler = callback -> true;
        return new A2aPushNotificationCallbackController(new InMemoryA2aPushNotificationCallbackStore(), handler,
                enabledGate(handler));
    }

    private static A2aPushNotificationCapabilityGate enabledGate(A2aPushNotificationCallbackHandler callbackHandler) {
        A2AProperties properties = new A2AProperties();
        properties.setPushNotifications(true);
        properties.getPushNotification().setTrustedCallbackHosts(List.of("caller.example"));
        return new A2aPushNotificationCapabilityGate(properties,
                new HttpPushNotificationSender(new InMemoryPushNotificationConfigStore(),
                        properties.getPushNotification()), new InMemoryA2aPushNotificationCallbackStore(),
                callbackHandler);
    }

    private static A2aPushNotificationCapabilityGate disabledGate() {
        A2AProperties properties = new A2AProperties();
        return new A2aPushNotificationCapabilityGate(properties,
                new HttpPushNotificationSender(new InMemoryPushNotificationConfigStore(),
                        properties.getPushNotification()), new InMemoryA2aPushNotificationCallbackStore(),
                callback -> true);
    }

    private static MockHttpServletRequest request(String notificationId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a/push-notifications/callback");
        request.addHeader("X-A2A-Notification-Id", notificationId);
        request.addHeader("Authorization", "Bearer token-ref");
        return request;
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
                      "contextId": "ctx-1",
                      "status": {"state": "TASK_STATE_COMPLETED"}
                    }
                  }%s
                }
                """.formatted(taskId, notification);
    }
}
