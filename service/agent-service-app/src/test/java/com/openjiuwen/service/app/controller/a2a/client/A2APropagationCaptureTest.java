/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.sun.net.httpserver.HttpServer;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentInterface;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

class A2APropagationCaptureTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realRemoteIoAndRetryUseCapturedHeadersForIdenticalContextIds(boolean retry) throws Exception {
        var observed = new CopyOnWriteArrayList<String>();
        var failedOnce = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a2a", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String trace = exchange.getRequestHeaders().getFirst("traceparent");
            observed.add(trace);
            if (retry && "first-trace".equals(trace) && failedOnce.compareAndSet(false, true)) {
                exchange.sendResponseHeaders(503, -1);
                exchange.close();
                return;
            }
            byte[] reply = """
                    {"jsonrpc":"2.0","id":"request","result":{"task":{"id":"remote-task",
                    "contextId":"same","status":{"state":"TASK_STATE_COMPLETED"},
                    "artifacts":[{"artifactId":"answer","parts":[{"text":"done"}]}]}}}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/a2a";
        var registry = new A2ARemoteAgentCardRegistry();
        registry.register("remote", AgentCard.builder().name("remote").description("remote").version("1")
                .capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .securitySchemes(Map.of()).securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", endpoint, null, "1.0")))
                .url(endpoint).preferredTransport("JSONRPC").additionalInterfaces(List.of()).build(), 10, false);
        AtomicReference<String> current = new AtomicReference<>("first-trace");
        var propagation = new A2APropagationHeaderProvider() {
            @Override
            public Map<String, String> headersFor(A2AOutboundRequest request) {
                return Map.of("traceparent", "uncaptured");
            }

            @Override
            public A2APropagationHeaderProvider capture() {
                String captured = current.get();
                return request -> Map.of("traceparent", captured);
            }
        };
        var client = new A2ARemoteAgentClient(registry);
        try (var registration = A2APropagationHeaderRegistry.registerProvider(propagation)) {
            var call = new RemoteCall("remote", "hello", "same", null, Map.of());
            var first = client.callOutcome(call, null);
            current.set("second-trace");
            var second = client.callOutcome(call, null);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
            assertThat(observed).containsExactlyInAnyOrderElementsOf(retry
                    ? List.of("first-trace", "first-trace", "second-trace")
                    : List.of("first-trace", "second-trace"));
        } finally {
            client.shutdown();
            server.stop(0);
        }
    }

    @Test
    void capturesEachInvocationBeforeHandoffAndRestoresWorkerAfterFailure() throws Exception {
        AtomicReference<String> selected = new AtomicReference<>("trace-a");
        var provider = new A2APropagationHeaderProvider() {
            @Override
            public Map<String, String> headersFor(A2AOutboundRequest request) {
                return Map.of("traceparent", selected.get());
            }

            @Override
            public A2APropagationHeaderProvider capture() {
                String captured = selected.get();
                return request -> Map.of("traceparent", captured);
            }
        };
        var executor = Executors.newSingleThreadExecutor();
        try (var registration = A2APropagationHeaderRegistry.registerProvider(provider)) {
            var first = A2APropagationHeaderRegistry.captureInvocation(
                    () -> A2APropagationHeaderRegistry.provide(null));
            selected.set("trace-b");
            var second = A2APropagationHeaderRegistry.captureInvocation(() -> {
                assertThat(A2APropagationHeaderRegistry.provide(null)).containsEntry("traceparent", "trace-b");
                throw new IllegalStateException("transport failed");
            });
            selected.set("worker-default");
            assertThatThrownBy(() -> executor.submit(second).get(5, TimeUnit.SECONDS))
                    .hasRootCauseMessage("transport failed");
            assertThat(executor.submit(first).get(5, TimeUnit.SECONDS))
                    .containsEntry("traceparent", "trace-a");
            assertThat(executor.submit(() -> A2APropagationHeaderRegistry.provide(null)).get(5, TimeUnit.SECONDS))
                    .containsEntry("traceparent", "worker-default");
        } finally {
            executor.shutdownNow();
        }
    }
}
