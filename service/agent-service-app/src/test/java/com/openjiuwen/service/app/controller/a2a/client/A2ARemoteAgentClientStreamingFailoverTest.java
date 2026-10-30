/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentEntry;
import com.openjiuwen.service.app.a2a.catalog.RemoteInstanceCandidate;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests of the streaming first-event failover semantics in
 * {@link A2ARemoteAgentClient} (FEAT-062 P11): a silent SSE candidate is
 * aborted by the transport-level first-event watchdog and the healthy
 * candidate completes the call, late events of an abandoned stream cannot
 * corrupt the outcome, an engaged instance never rotates and fails within the
 * bounded total budget, and stream-end or connection faults keep their
 * classification.
 *
 * @since 0.1.4
 */
class A2ARemoteAgentClientStreamingFailoverTest {
    // SSE data fields terminate at newlines, so the JSON-RPC payload stays one line
    private static final byte[] SSE_TASK_COMPLETED = sseEvent(
            "{\"jsonrpc\":\"2.0\",\"id\":\"request\",\"result\":{\"task\":{\"id\":\"remote-task\","
                    + "\"contextId\":\"same\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"},"
                    + "\"artifacts\":[{\"artifactId\":\"answer\",\"parts\":[{\"text\":\"done\"}]}]}}}");

    // final defaults to false, so the non-final status update omits the field
    private static final byte[] SSE_TASK_WORKING = sseEvent(
            "{\"jsonrpc\":\"2.0\",\"id\":\"request\",\"result\":{\"statusUpdate\":{\"taskId\":\"remote-task\","
                    + "\"contextId\":\"same\",\"status\":{\"state\":\"TASK_STATE_WORKING\"}}}}");

    private static final CandidateSelector FIXED_FIRST = candidates -> candidates.get(0);

    private final List<String> observed = new CopyOnWriteArrayList<>();

    private final CountDownLatch holdOpen = new CountDownLatch(1);

    private final CountDownLatch lateEventDelivered = new CountDownLatch(1);

    private HttpServer server;

    private ExecutorService serverExecutor;

    @AfterEach
    void stopServer() {
        holdOpen.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
        }
    }

    @Test
    void silentStreamIsAbortedAndHealthyCandidateCompletesTheCall() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 1, true, url("/silent"), url("/sse-ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(streamingCall(), null);

            RemoteCallOutcome outcome = result.get(15, TimeUnit.SECONDS);
            assertThat(outcome.result()).isEqualTo("done");
            assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(observed).containsExactly("/silent", "/sse-ok");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void lateEventOfAbandonedStreamCannotCorruptTheOutcome() throws Exception {
        startServer();
        // the late event is written 1.5s after the send: the watchdog (1s) has
        // already abandoned the stream and rotated, so the event must be
        // dropped instead of engaging the result
        A2ARemoteAgentCardRegistry registry = registry("remote", 1, true, url("/late"), url("/sse-ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(streamingCall(), null);

            RemoteCallOutcome outcome = result.get(15, TimeUnit.SECONDS);
            assertThat(outcome.result()).isEqualTo("done");
            assertThat(lateEventDelivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result).isCompletedWithValueMatching(completed -> "done".equals(completed.result()));
            assertThat(observed).containsExactly("/late", "/sse-ok");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void engagedInstanceNeverRotatesAndFailsWithinTotalBudget() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 1, true, url("/engaged"), url("/sse-ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            long startNanos = System.nanoTime();
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(streamingCall(), null);

            Throwable thrown = catchThrowable(() -> result.get(15, TimeUnit.SECONDS));
            assertThat(thrown).isInstanceOf(ExecutionException.class);
            assertThat(thrown.getCause()).isInstanceOf(TimeoutException.class);
            // the first remote event engaged the instance: the healthy second
            // candidate must never receive a send, and the failure must come
            // from the total budget instead of the 1s attempt timeout
            assertThat(observed).containsExactly("/engaged");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            assertThat(elapsedMillis).isGreaterThan(TimeUnit.SECONDS.toMillis(1));
        } finally {
            client.shutdown();
        }
    }

    @Test
    void abruptStreamCloseBeforeFirstEventFailsWithoutRotating() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 20, true, url("/abrupt"), url("/sse-ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(streamingCall(), null);

            Throwable thrown = catchThrowable(() -> result.get(15, TimeUnit.SECONDS));
            assertThat(thrown).isInstanceOf(ExecutionException.class);
            assertThat(thrown.getCause()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("closed the stream before a terminal event");
            assertThat(observed).containsExactly("/abrupt");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void connectionRefusedCandidateRotatesToHealthyCandidate() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 5, true, deadEndpoint(), url("/sse-ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(streamingCall(), null);

            RemoteCallOutcome outcome = result.get(15, TimeUnit.SECONDS);
            assertThat(outcome.result()).isEqualTo("done");
            assertThat(observed).containsExactly("/sse-ok");
        } finally {
            client.shutdown();
        }
    }

    private static RemoteCall streamingCall() {
        return new RemoteCall("remote", "hello", "same", null, Map.of(), null, true);
    }

    private static byte[] sseEvent(String json) {
        return ("data: " + json.strip() + "\n\n").getBytes(StandardCharsets.UTF_8);
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // a held-open SSE exchange must not starve concurrent requests: the
        // default executor serves all exchanges on one thread
        serverExecutor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                Executors.defaultThreadFactory());
        server.setExecutor(serverExecutor);
        server.createContext("/sse-ok", this::replyCompletedTask);
        server.createContext("/silent", this::holdSilentStream);
        server.createContext("/late", this::replyLateWorkingEvent);
        server.createContext("/engaged", this::replyWorkingThenHold);
        server.createContext("/abrupt", this::closeAbruptly);
        server.start();
    }

    private void replyCompletedTask(HttpExchange exchange) throws IOException {
        try (exchange) {
            observed.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(SSE_TASK_COMPLETED);
                out.flush();
            }
        }
    }

    private void holdSilentStream(HttpExchange exchange) throws IOException {
        try (exchange) {
            observed.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            holdOpen.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException stopped) {
            // the executor shutdown interrupts the hold; the pooled thread exits with it
            return;
        }
    }

    private void replyLateWorkingEvent(HttpExchange exchange) throws IOException {
        try (exchange) {
            observed.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            TimeUnit.MILLISECONDS.sleep(1_500L);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(SSE_TASK_WORKING);
                out.flush();
            }
        } catch (InterruptedException stopped) {
            // the executor shutdown interrupts the late write; the latch below still releases
            return;
        } finally {
            lateEventDelivered.countDown();
        }
    }

    private void replyWorkingThenHold(HttpExchange exchange) throws IOException {
        try (exchange) {
            observed.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(SSE_TASK_WORKING);
                out.flush();
                holdOpen.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                // the executor shutdown interrupts the hold; the response is already committed
                return;
            }
        }
    }

    private void closeAbruptly(HttpExchange exchange) throws IOException {
        observed.add(exchange.getRequestURI().getPath());
        exchange.getRequestBody().readAllBytes();
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        exchange.close();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static String deadEndpoint() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    private static A2ARemoteAgentCardRegistry registry(String name, int timeoutSeconds, boolean isStreaming,
            String... endpoints) {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        List<RemoteInstanceCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < endpoints.length; i++) {
            candidates.add(new RemoteInstanceCandidate("i-" + (i + 1), endpoints[i], 0));
        }
        registry.register(new RemoteAgentEntry(name, card(name, endpoints[0]), timeoutSeconds,
            isStreaming, null, candidates));
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
