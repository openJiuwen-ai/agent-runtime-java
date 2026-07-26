/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.a2aproject.sdk.server.tasks.PushNotificationConfigStore;
import org.a2aproject.sdk.server.tasks.PushNotificationSender;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Integration journey for runtime-to-runtime push notification delivery.
 */
@SpringBootTest(classes = TestServiceApplication.class, properties = {
    "openjiuwen.service.a2a.push-notifications=true"
})
class PushNotificationSenderIntegrationTest {
    @Autowired
    private PushNotificationSender sender;

    @Autowired
    private PushNotificationConfigStore pushConfigStore;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void springConfiguredSenderPostsJsonRpcCallbackWithAuthorization() throws Exception {
        CallbackCapture capture = startCallbackServer();
        pushConfigStore.setInfo(TaskPushNotificationConfig.builder()
            .id("push-runtime-to-runtime")
            .taskId("task-runtime-to-runtime")
            .url(callbackUrl())
            .token("runtime-token")
            .build());

        sender.sendNotification(completedEvent(), completedTask());

        assertThat(capture.received.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(capture.notificationId()).isNotBlank();
        assertThat(capture.authorization()).isEqualTo("Bearer runtime-token");
        Map<String, Object> json = mapper.readValue(capture.body(), Map.class);
        assertThat(json).containsEntry("jsonrpc", "2.0");
        assertThat(json.get("notificationId")).isEqualTo(capture.notificationId());
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        Map<String, Object> task = (Map<String, Object>) result.get("task");
        assertThat(task).containsEntry("id", "task-runtime-to-runtime").containsEntry("contextId",
            "ctx-runtime-to-runtime");
        assertThat((Map<String, Object>) task.get("status")).containsEntry("state", "TASK_STATE_COMPLETED");
    }

    @Test
    void springConfiguredSenderPostsCallbackWithoutDeploymentHostTrust() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a/push-notifications/callback", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 202, "{\"status\":\"accepted\"}");
        });
        server.start();
        pushConfigStore.setInfo(TaskPushNotificationConfig.builder()
            .id("push-no-host-trust-runtime")
            .taskId("task-no-host-trust-runtime")
            .url(callbackUrl())
            .token("runtime-token")
            .build());

        sender.sendNotification(completedEvent("task-no-host-trust-runtime"),
            completedTask("task-no-host-trust-runtime"));

        assertThat(requests.get()).isEqualTo(1);
    }

    private String callbackUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a/push-notifications/callback";
    }

    private CallbackCapture startCallbackServer() throws IOException {
        CallbackCapture capture = new CallbackCapture(new CountDownLatch(1), new AtomicReference<>(),
            new AtomicReference<>(), new AtomicReference<>());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a/push-notifications/callback", capture::accept);
        server.start();
        return capture;
    }

    private static Task completedTask() {
        return completedTask("task-runtime-to-runtime");
    }

    private static Task completedTask(String taskId) {
        return Task.builder()
            .id(taskId)
            .contextId("ctx-runtime-to-runtime")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .build();
    }

    private static TaskStatusUpdateEvent completedEvent() {
        return completedEvent("task-runtime-to-runtime");
    }

    private static TaskStatusUpdateEvent completedEvent(String taskId) {
        return TaskStatusUpdateEvent.builder()
            .taskId(taskId)
            .contextId("ctx-runtime-to-runtime")
            .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
            .build();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private record CallbackCapture(CountDownLatch received, AtomicReference<String> notificationIdRef,
            AtomicReference<String> authorizationRef, AtomicReference<String> bodyRef) {
        void accept(HttpExchange exchange) throws IOException {
            notificationIdRef.set(exchange.getRequestHeaders().getFirst("X-A2A-Notification-Id"));
            authorizationRef.set(exchange.getRequestHeaders().getFirst("Authorization"));
            bodyRef.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"status\":\"accepted\"}");
            received.countDown();
        }

        String notificationId() {
            return notificationIdRef.get();
        }

        String authorization() {
            return authorizationRef.get();
        }

        String body() {
            return bodyRef.get();
        }
    }
}
