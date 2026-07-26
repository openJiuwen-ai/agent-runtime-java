/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openjiuwen.service.app.config.A2AProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.a2aproject.sdk.server.tasks.InMemoryPushNotificationConfigStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskPushNotificationConfig;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests HTTP push notification delivery.
 */
class PushNotificationSenderTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postsJsonRpcTaskResultWithStableNotificationIdAndAuthorization() throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicReference<String> notificationId = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a/push-notifications/callback", exchange -> {
            notificationId.set(exchange.getRequestHeaders().getFirst("X-A2A-Notification-Id"));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"status\":\"accepted\"}");
            received.countDown();
        });
        server.start();
        InMemoryPushNotificationConfigStore store = configStore(callbackUrl(), "secret-token");
        HttpPushNotificationSender sender = new HttpPushNotificationSender(store);
        Task task = completedTask("task-1");
        TaskStatusUpdateEvent event = completedEvent("task-1");
        String expectedNotificationId = sender.notificationIdFor(task,
                store.getInfo(new org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams("task-1")).configs()
                        .get(0), event);

        sender.sendNotification(event, task);

        assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(notificationId.get()).isEqualTo(expectedNotificationId);
        assertThat(authorization.get()).isEqualTo("Bearer secret-token");
        JsonObject json = JsonParser.parseString(body.get()).getAsJsonObject();
        assertThat(json.get("jsonrpc").getAsString()).isEqualTo("2.0");
        assertThat(json.get("notificationId").getAsString()).isEqualTo(expectedNotificationId);
        assertThat(json.getAsJsonObject("result").getAsJsonObject("task").get("id").getAsString()).isEqualTo("task-1");
        assertThat(sender.deliveryRecord(expectedNotificationId)).hasValueSatisfying(record -> {
            assertThat(record.attempts()).isEqualTo(1);
            assertThat(record.isSuccess()).isTrue();
        });
    }

    @Test
    void senderCallbackBodyIsAcceptedByReceiver() {
        A2AProperties properties = new A2AProperties();
        HttpPushNotificationSender sender = new HttpPushNotificationSender(new InMemoryPushNotificationConfigStore());
        A2aPushNotificationCallbackHandler handler = callback -> true;
        properties.setPushNotifications(true);
        A2aPushNotificationCallbackController receiver = new A2aPushNotificationCallbackController(
                new InMemoryA2aPushNotificationCallbackStore(), handler,
                new A2aPushNotificationCapabilityGate(properties, sender,
                        new InMemoryA2aPushNotificationCallbackStore(), handler));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/a2a/push-notifications/callback");
        request.addHeader("X-A2A-Notification-Id", "notif-sender-receiver");

        ResponseEntity<String> response = receiver.handleCallback(sender.callbackBody("notif-sender-receiver",
                completedTask("task-1")), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void rejectsNonHttpCallbackUrlWithoutPosting() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a/push-notifications/callback", exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, "{}");
        });
        server.start();
        InMemoryPushNotificationConfigStore store = configStore("file:///tmp/callback", null);
        HttpPushNotificationSender sender = new HttpPushNotificationSender(store);
        Task task = completedTask("task-1");
        TaskStatusUpdateEvent event = completedEvent("task-1");

        sender.sendNotification(event, task);

        assertThat(requests.get()).isZero();
        String notificationId = sender.notificationIdFor(task,
                store.getInfo(new org.a2aproject.sdk.spec.ListTaskPushNotificationConfigsParams("task-1")).configs()
                        .get(0), event);
        assertThat(sender.deliveryRecord(notificationId)).hasValueSatisfying(record -> {
            assertThat(record.attempts()).isEqualTo(1);
            assertThat(record.isSuccess()).isFalse();
            assertThat(record.message()).contains("invalid callback URL");
        });
    }

    @Test
    void failedDeliveryCanBeRetriedWithSameNotificationId() throws Exception {
        CountDownLatch received = new CountDownLatch(2);
        AtomicReference<String> firstNotificationId = new AtomicReference<>();
        AtomicReference<String> secondNotificationId = new AtomicReference<>();
        AtomicInteger requests = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a/push-notifications/callback", exchange -> {
            int attempt = requests.incrementAndGet();
            if (attempt == 1) {
                firstNotificationId.set(exchange.getRequestHeaders().getFirst("X-A2A-Notification-Id"));
                respond(exchange, 503, "{\"status\":\"busy\"}");
            } else {
                secondNotificationId.set(exchange.getRequestHeaders().getFirst("X-A2A-Notification-Id"));
                respond(exchange, 202, "{\"status\":\"accepted\"}");
            }
            received.countDown();
        });
        server.start();
        InMemoryPushNotificationConfigStore store = configStore(callbackUrl(), null);
        HttpPushNotificationSender sender = new HttpPushNotificationSender(store);
        Task task = completedTask("task-1");
        TaskStatusUpdateEvent event = completedEvent("task-1");

        sender.sendNotification(event, task);
        sender.sendNotification(event, task);

        assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(secondNotificationId.get()).isEqualTo(firstNotificationId.get());
        assertThat(sender.deliveryRecord(firstNotificationId.get())).hasValueSatisfying(record -> {
            assertThat(record.attempts()).isEqualTo(2);
            assertThat(record.isSuccess()).isTrue();
            assertThat(record.message()).isEqualTo("HTTP 202");
        });
    }

    private String callbackUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a/push-notifications/callback";
    }

    private static InMemoryPushNotificationConfigStore configStore(String callbackUrl, String token) {
        InMemoryPushNotificationConfigStore store = new InMemoryPushNotificationConfigStore();
        store.setInfo(TaskPushNotificationConfig.builder().id("push-1").taskId("task-1").url(callbackUrl).token(token)
                .build());
        return store;
    }

    private static Task completedTask(String taskId) {
        return Task.builder().id(taskId).contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
    }

    private static TaskStatusUpdateEvent completedEvent(String taskId) {
        return TaskStatusUpdateEvent.builder().taskId(taskId).contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
