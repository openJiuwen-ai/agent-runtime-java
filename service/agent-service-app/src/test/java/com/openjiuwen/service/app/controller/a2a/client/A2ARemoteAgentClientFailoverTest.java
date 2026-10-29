/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentEntry;
import com.openjiuwen.service.app.a2a.catalog.RemoteInstanceCandidate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.a2aproject.sdk.spec.A2AClientException;
import org.a2aproject.sdk.spec.A2AClientHTTPError;
import org.a2aproject.sdk.spec.A2AServerException;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests of the multi-instance failover in {@link A2ARemoteAgentClient} (FEAT-062
 * L2 {@literal §}4): transient failures rotate to the next candidate in the
 * round, a stalled remote times out instead of rotating, an exhausted sweep
 * budget completes exceptionally after the retry budget, catalog misses and
 * empty candidate lists fail fast, and the transport-failure classification.
 *
 * @since 0.1.4
 */
class A2ARemoteAgentClientFailoverTest {
    private static final byte[] TASK_REPLY = """
            {"jsonrpc":"2.0","id":"request","result":{"task":{"id":"remote-task",
            "contextId":"same","status":{"state":"TASK_STATE_COMPLETED"},
            "artifacts":[{"artifactId":"answer","parts":[{"text":"done"}]}]}}}
            """.getBytes(StandardCharsets.UTF_8);

    private static final byte[] TASK_ERROR_REPLY = """
            {"jsonrpc":"2.0","id":"request","error":{"code":-32000,
            "message":"permanent business rejection"}}
            """.getBytes(StandardCharsets.UTF_8);

    private static final byte[] TASK_WORKING_REPLY = """
            {"jsonrpc":"2.0","id":"request","result":{"task":{"id":"remote-task",
            "contextId":"same","status":{"state":"TASK_STATE_WORKING"},"artifacts":[]}}}
            """.getBytes(StandardCharsets.UTF_8);

    private static final CandidateSelector FIXED_FIRST = candidates -> candidates.get(0);

    private final List<String> observed = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void transientFirstCandidateFailsOverToTheNextInstance() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 20, url("/fail"), url("/ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(
                    new RemoteCall("remote", "hello", "same", null, Map.of()), null);

            RemoteCallOutcome outcome = result.get(15, TimeUnit.SECONDS);
            assertThat(outcome.result()).isEqualTo("done");
            assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(observed).containsExactly("/fail", "/ok");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void jsonRpcErrorEnvelopeDoesNotRotate() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 20, url("/error"), url("/ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            assertThatThrownBy(() -> client
                    .callOutcome(new RemoteCall("remote", "hello", "same", null, Map.of()), null)
                    .get(15, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(observed).containsExactly("/error");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void invalidProtocolResponseDoesNotRotate() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 20, url("/invalid"), url("/ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            assertThatThrownBy(() -> client
                    .callOutcome(new RemoteCall("remote", "hello", "same", null, Map.of()), null)
                    .get(15, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
            assertThat(observed).containsExactly("/invalid");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void stalledRemoteTimesOutWithoutRotatingToTheNextCandidate() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 1, url("/working"), url("/ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(
                    new RemoteCall("remote", "hello", "same", null, Map.of()), null);

            Throwable thrown = catchThrowable(() -> result.get(15, TimeUnit.SECONDS));
            assertThat(thrown).isInstanceOf(ExecutionException.class);
            assertThat(thrown.getCause()).isInstanceOf(TimeoutException.class);
            assertThat(observed).containsExactly("/working");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void transientFailuresExhaustRetryBudgetThenFailExceptionally() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 20, url("/fail"), url("/fail"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        ReflectionTestUtils.setField(client, "retryBackoffBaseMillis", 1L);
        try {
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(
                    new RemoteCall("remote", "hello", "same", null, Map.of()), null);

            Throwable thrown = catchThrowable(() -> result.get(15, TimeUnit.SECONDS));
            assertThat(thrown).isInstanceOf(ExecutionException.class);
            assertThat(thrown.getCause()).isInstanceOf(A2AClientException.class);
            assertThat(observed).size().isEqualTo(8);
        } finally {
            client.shutdown();
        }
    }

    @Test
    void noLiveCandidateRejectsTheCallWithoutNetwork() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 20, url("/ok"));
        registry.register(new RemoteAgentEntry("remote", card("remote", url("/ok")), 20, false, null, List.of()));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            assertThatThrownBy(() -> client.callOutcome(new RemoteCall("remote", "hello", "same", null, Map.of()),
                    null)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("Remote agent 'remote' has no live instance candidate");
            assertThat(observed).isEmpty();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void unknownAgentFailsFastWithTheCatalogMiss() throws Exception {
        startServer();
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(new A2ARemoteAgentCardRegistry(), 4, null, FIXED_FIRST);
        try {
            assertThatThrownBy(() -> client.callOutcome(new RemoteCall("ghost", "hello", "same", null, Map.of()),
                    null)).isInstanceOf(IllegalStateException.class).hasMessage("Unknown remote agent: ghost");
            assertThat(observed).isEmpty();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void onlyGuaranteedNotAcceptedTransportFailuresAreRetryable() {
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(new A2AClientHTTPError(500, "boom",
                (Object) null))).isTrue();
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(new A2AClientHTTPError(503, "boom",
                (Object) null))).isTrue();
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(new A2AClientHTTPError(400, "bad",
                (Object) null))).isFalse();
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(new A2AClientException(
                new A2AClientHTTPError(503, "unavailable", (Object) null)))).isTrue();
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(new A2AClientException(
                new A2AClientHTTPError(400, "bad", (Object) null)))).isFalse();
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(
                new A2AClientException("connection refused", new ConnectException("connection refused"))))
                .isTrue();
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(
                new A2AClientException(new HttpTimeoutException("timed out")))).isTrue();
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(
                new A2AClientException(new SocketTimeoutException("timed out")))).isTrue();
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(new A2AClientException("invalid JSON")))
                .isFalse();
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(new A2AServerException("server side"))).isFalse();
        assertThat(A2ARemoteAgentClient.isRetryableTransportFailure(new IllegalStateException("unclassified")))
                .isFalse();
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> reply(exchange, TASK_REPLY));
        server.createContext("/error", exchange -> reply(exchange, TASK_ERROR_REPLY));
        server.createContext("/invalid", exchange -> reply(exchange,
                "{\"jsonrpc\":\"2.0\",\"id\":\"request\",\"result\":42}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/working", exchange -> reply(exchange, TASK_WORKING_REPLY));
        server.createContext("/fail", exchange -> {
            observed.add("/fail");
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        server.start();
    }

    private void reply(HttpExchange exchange, byte[] body) throws IOException {
        try (exchange) {
            observed.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static A2ARemoteAgentCardRegistry registry(String name, int timeoutSeconds, String... endpoints) {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        List<RemoteInstanceCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < endpoints.length; i++) {
            candidates.add(new RemoteInstanceCandidate("i-" + (i + 1), endpoints[i], 0));
        }
        registry.register(new RemoteAgentEntry(name, card(name, endpoints[0]), timeoutSeconds,
            false, null, candidates));
        return registry;
    }

    private static AgentCard card(String name, String endpoint) {
        AgentInterface iface = new AgentInterface("JSONRPC", endpoint, null, "1.0");
        return AgentCard.builder().name(name).description(name).version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .securitySchemes(Map.of()).securityRequirements(List.of())
                .supportedInterfaces(List.of(iface)).url(endpoint)
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }
}
