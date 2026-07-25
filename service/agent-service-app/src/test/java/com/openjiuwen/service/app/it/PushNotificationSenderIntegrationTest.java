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
    "openjiuwen.service.a2a.push-notifications=true",
    "openjiuwen.service.a2a.push-notification.trusted-callback-hosts[0]=127.0.0.1"
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
    void springConfiguredSenderPostsJsonRpcCallbackToTrustedRuntime() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> notificationIdHeader = new AtomicReference<>();
        AtomicReference<String> authorizationHeader = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a/push-notifications/callback", exchange -> {
            notificationIdHeader.set(exchange.getRequestHeaders().getFirst("X-A2A-Notification-Id"));
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 202, "{\"status\":\"accepted\"}");
            received.countDown();
        });
        server.start();
        pushConfigStore.setInfo(TaskPushNotificationConfig.builder()
            .id("push-runtime-to-runtime")
            .taskId("task-runtime-to-runtime")
            .url(callbackUrl())
            .token("runtime-token")
            .build());

        sender.sendNotification(completedEvent(), completedTask());

        assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(notificationIdHeader.get()).isNotBlank();
        assertThat(authorizationHeader.get()).isEqualTo("Bearer runtime-token");
        Map<String, Object> json = mapper.readValue(body.get(), Map.class);
        assertThat(json).containsEntry("jsonrpc", "2.0");
        assertThat(json.get("notificationId")).isEqualTo(notificationIdHeader.get());
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        Map<String, Object> task = (Map<String, Object>) result.get("task");
        assertThat(task).containsEntry("id", "task-runtime-to-runtime").containsEntry("contextId",
            "ctx-runtime-to-runtime");
        assertThat((Map<String, Object>) task.get("status")).containsEntry("state", "TASK_STATE_COMPLETED");
    }

    @Test
    void springConfiguredSenderRejectsUntrustedCallbackHostWithoutPosting() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a/push-notifications/callback", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 202, "{\"status\":\"accepted\"}");
        });
        server.start();
        pushConfigStore.setInfo(TaskPushNotificationConfig.builder()
            .id("push-untrusted-runtime")
            .taskId("task-untrusted-runtime")
            .url(untrustedCallbackUrl())
            .token("runtime-token")
            .build());

        sender.sendNotification(completedEvent("task-untrusted-runtime"), completedTask("task-untrusted-runtime"));

        assertThat(requests.get()).isZero();
    }

    private String callbackUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a/push-notifications/callback";
    }

    private String untrustedCallbackUrl() {
        return "http://localhost:" + server.getAddress().getPort() + "/a2a/push-notifications/callback";
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
}
