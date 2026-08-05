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
    void opensSubscribeToTaskSseWithStreamReference() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        AtomicReference<String> streamReference = new AtomicReference<>();
        CountDownLatch requested = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange -> respond(exchange, requestBody, streamReference, requested));
        server.start();

        A2ATaskSubscriptionClient client = new A2ATaskSubscriptionClient();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        A2ATaskSubscriptionClient.TaskSubscription subscription = client.subscribe(
                new A2ATaskSubscriptionClient.TaskSubscriptionRequest(endpoint, "task-1", "stream-1"),
                ignored -> { }, completed::countDown, ignored -> { });

        assertThat(requested.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(completed.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(streamReference.get()).isEqualTo("stream-1");
        assertThat(requestBody.get()).contains("\"method\":\"SubscribeToTask\"")
                .contains("\"id\":\"task-1\"");
        subscription.close();
    }

    @Test
    void normalizesRuntimeOriginAndExistingA2aEndpoint() {
        assertThat(A2ATaskSubscriptionClient.a2aEndpoint("http://runtime:8080"))
                .isEqualTo("http://runtime:8080/a2a");
        assertThat(A2ATaskSubscriptionClient.a2aEndpoint("http://runtime:8080/a2a/"))
                .isEqualTo("http://runtime:8080/a2a");
    }

    private static void respond(HttpExchange exchange, AtomicReference<String> requestBody,
            AtomicReference<String> streamReference, CountDownLatch requested) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        streamReference.set(exchange.getRequestHeaders().getFirst(A2ATaskSubscriptionClient.STREAM_REFERENCE_HEADER));
        requested.countDown();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.getResponseBody().close();
        exchange.close();
    }
}
