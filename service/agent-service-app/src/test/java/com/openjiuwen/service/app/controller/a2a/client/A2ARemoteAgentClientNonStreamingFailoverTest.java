/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Tests of the non-streaming response-wait failover semantics in
 * {@link A2ARemoteAgentClient} (FEAT-062 P11 follow-up): a silent non-streaming
 * candidate is aborted by the transport-level POST response watchdog and the
 * healthy candidate completes the call, the late response of an abandoned POST
 * cannot corrupt the outcome, and a connection-refused candidate still rotates
 * in non-streaming mode.
 *
 * @since 0.1.4
 */
class A2ARemoteAgentClientNonStreamingFailoverTest {
    // the non-streaming JSON-RPC response carries the task under result.task,
    // the same envelope the runtime's own server answers message/send with
    private static final byte[] JSON_TASK_COMPLETED = jsonTaskCompleted();

    private static final CandidateSelector FIXED_FIRST = candidates -> candidates.get(0);

    private final List<String> observed = new CopyOnWriteArrayList<>();

    private final CountDownLatch holdOpen = new CountDownLatch(1);

    private final CountDownLatch lateResponseDelivered = new CountDownLatch(1);

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
    void silentPostIsAbortedAndHealthyCandidateCompletesTheCall() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 1, false, url("/silent-post"), url("/post-ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(nonStreamingCall(), null);

            RemoteCallOutcome outcome = result.get(15, TimeUnit.SECONDS);
            assertThat(outcome.result()).isEqualTo("done");
            assertThat(outcome.remoteState()).isEqualTo(TaskState.TASK_STATE_COMPLETED);
            assertThat(observed).containsExactly("/silent-post", "/post-ok");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void lateResponseOfAbandonedPostCannotCorruptTheOutcome() throws Exception {
        startServer();
        // the late response is written 1.5s after the send: the watchdog (1s) has
        // already abandoned the attempt and rotated, so the response must be
        // discarded instead of engaging the result
        A2ARemoteAgentCardRegistry registry = registry("remote", 1, false, url("/late-post"), url("/post-ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(nonStreamingCall(), null);

            RemoteCallOutcome outcome = result.get(15, TimeUnit.SECONDS);
            assertThat(outcome.result()).isEqualTo("done");
            assertThat(lateResponseDelivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(result).isCompletedWithValueMatching(completed -> "done".equals(completed.result()));
            assertThat(observed).containsExactly("/late-post", "/post-ok");
        } finally {
            client.shutdown();
        }
    }

    @Test
    void connectionRefusedPostRotatesToHealthyCandidate() throws Exception {
        startServer();
        A2ARemoteAgentCardRegistry registry = registry("remote", 5, false, deadEndpoint(), url("/post-ok"));
        A2ARemoteAgentClient client = new A2ARemoteAgentClient(registry, 4, null, FIXED_FIRST);
        try {
            CompletableFuture<RemoteCallOutcome> result = client.callOutcome(nonStreamingCall(), null);

            RemoteCallOutcome outcome = result.get(15, TimeUnit.SECONDS);
            assertThat(outcome.result()).isEqualTo("done");
            assertThat(observed).containsExactly("/post-ok");
        } finally {
            client.shutdown();
        }
    }

    private static RemoteCall nonStreamingCall() {
        return new RemoteCall("remote", "hello", "same", null, Map.of(), null, false);
    }

    private static byte[] jsonTaskCompleted() {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":\"request\",\"result\":{\"task\":{\"id\":\"remote-task\","
                + "\"contextId\":\"same\",\"status\":{\"state\":\"TASK_STATE_COMPLETED\"},"
                + "\"artifacts\":[{\"artifactId\":\"answer\",\"parts\":[{\"text\":\"done\"}]}]}}}";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    private void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // a held-open exchange must not starve concurrent requests: the default
        // executor serves all exchanges on one thread
        serverExecutor = new ThreadPoolExecutor(4, 4, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(),
                Executors.defaultThreadFactory());
        server.setExecutor(serverExecutor);
        server.createContext("/post-ok", this::replyCompletedTask);
        server.createContext("/silent-post", this::holdSilentPost);
        server.createContext("/late-post", this::replyLateCompletedTask);
        server.start();
    }

    private void replyCompletedTask(HttpExchange exchange) throws IOException {
        try (exchange) {
            observed.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, JSON_TASK_COMPLETED.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(JSON_TASK_COMPLETED);
            }
        }
    }

    private void holdSilentPost(HttpExchange exchange) throws IOException {
        try (exchange) {
            observed.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            holdOpen.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException stopped) {
            // the executor shutdown interrupts the hold; the pooled thread exits with it
            return;
        }
    }

    private void replyLateCompletedTask(HttpExchange exchange) throws IOException {
        try (exchange) {
            observed.add(exchange.getRequestURI().getPath());
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            TimeUnit.MILLISECONDS.sleep(1_500L);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(JSON_TASK_COMPLETED);
                out.flush();
            }
        } catch (InterruptedException stopped) {
            // the executor shutdown interrupts the late write; the latch below still releases
            return;
        } finally {
            lateResponseDelivered.countDown();
        }
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
