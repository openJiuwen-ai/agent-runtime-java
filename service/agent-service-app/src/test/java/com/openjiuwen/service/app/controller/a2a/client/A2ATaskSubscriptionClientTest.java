/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests the reusable standard A2A Task subscription client.
 */
class A2ATaskSubscriptionClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void opensSubscribeToTaskSseWithCallerProvidedHeader() throws Exception {
        SubscriptionRequestCapture capture = new SubscriptionRequestCapture();
        CountDownLatch completed = new CountDownLatch(1);
        startSubscriptionEndpoint(capture);

        A2ATaskSubscriptionClient client = new A2ATaskSubscriptionClient();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        A2ATaskSubscriptionClient.TaskSubscription subscription = client.subscribe(
                new A2ATaskSubscriptionClient.TaskSubscriptionRequest(endpoint, "task-1",
                        Map.of("X-Test-Subscription", "subscription-1")),
                ignored -> { }, completed::countDown, ignored -> { });

        assertThat(capture.requested.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(capture.requestHeader.get()).isEqualTo("subscription-1");
        assertThat(capture.requestBody.get()).contains("\"method\":\"SubscribeToTask\"")
                .contains("\"id\":\"task-1\"");
        subscription.close();
    }

    @Test
    void normalizesRuntimeOriginAndExistingA2aEndpoint() {
        assertThat(A2ATaskSubscriptionClient.a2aEndpoint("http://runtime:8080"))
                .isEqualTo("http://runtime:8080/a2a");
        assertThat(new A2ATaskSubscriptionClient.TaskSubscriptionRequest(
                "http://runtime:8080", "task-1").requestHeaders()).isEmpty();
        assertThat(A2ATaskSubscriptionClient.a2aEndpoint("http://runtime:8080/a2a/"))
                .isEqualTo("http://runtime:8080/a2a");
    }

    private void startSubscriptionEndpoint(SubscriptionRequestCapture capture) throws IOException {
        HttpServer subscriptionServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        subscriptionServer.createContext("/a2a", exchange -> respond(exchange, capture));
        subscriptionServer.start();
        server = subscriptionServer;
    }

    private static void respond(HttpExchange exchange, SubscriptionRequestCapture capture) throws IOException {
        capture.requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        capture.requestHeader.set(exchange.getRequestHeaders().getFirst("X-Test-Subscription"));
        capture.requested.countDown();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
        exchange.close();
    }

    private static final class SubscriptionRequestCapture {
        private final AtomicReference<String> requestBody = new AtomicReference<>();
        private final AtomicReference<String> requestHeader = new AtomicReference<>();
        private final CountDownLatch requested = new CountDownLatch(1);
    }
}
