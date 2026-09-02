/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * L2 interop test (design FEAT-036 §3.1/§3.2): the "remote lowcode workflow" is a
 * plain JDK {@link HttpServer} stub whose JSON-RPC responses are hand-written per the
 * A2A 1.0.0 wire specification — deliberately NOT generated through our SDK — so the
 * test proves {@code A2ARemoteAgentClient} interoperates with an independent
 * implementation instead of merely round-tripping our own serialization.
 */
@SpringBootTest(classes = L2StubRemotePartsIntegrationTest.CallerRuntimeApplication.class, webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.application.name=caller-l2-stub-it"})
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = ClassMode.AFTER_CLASS)
class L2StubRemotePartsIntegrationTest {
    private static final String RAW_TEXT = "lowcode-interop-raw-payload";

    private static final String RAW_BASE64 = Base64.getEncoder()
            .encodeToString(RAW_TEXT.getBytes(StandardCharsets.UTF_8));

    /** Hand-written 1.0.0 stub result text (feeds back through remoteToolResults). */
    private static final String STUB_RESULT_TEXT = "lowcode stub analyzed url/raw/data parts";

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private A2ARemoteAgentCardRegistry registry;

    @Autowired
    private com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller remoteCaller;

    @Autowired
    private org.a2aproject.sdk.server.tasks.TaskStore taskStore;

    @LocalServerPort
    private int callerPort;

    private final ObjectMapper mapper = new ObjectMapper();

    private HttpServer stub;

    private final ConcurrentLinkedQueue<String> capturedBodies = new ConcurrentLinkedQueue<>();

    private final AtomicInteger requestCount = new AtomicInteger();

    /** Number of leading requests the stub answers with HTTP 500 (retry rehearsal, FEAT-036 §2.4 step 5). */
    private final AtomicInteger failFirstN = new AtomicInteger();

    @BeforeEach
    void startStub() throws IOException {
        CallerHandler.SHADOW_SNAPSHOTS.clear();
        CallerHandler.handlerTaskStore = taskStore;
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", this::handleStub);
        stub.start();
        registry.register("lowcode", card(stub.getAddress().getPort()), 5, false);
    }

    @AfterEach
    void stopStub() {
        if (stub != null) {
            stub.stop(0);
        }
    }

    @Test
    void remoteClientCompletesDelegateAgainstHandWrittenV100Stub() throws Exception {
        ResponseEntity<String> first = postA2a(rpc("SendMessage", "l2-stub-start",
                Map.of("message", Map.of("role", "ROLE_USER", "messageId", "msg-l2-start", "contextId", "ctx-l2-stub",
                        "parts", List.of(Map.of("text", "delegate to lowcode stub"))))));

        Task completed = awaitCompletedTask(taskId(first));

        // our client parsed the hand-written 1.0.0 response and resumed the caller task
        assertThat(completed.status().state()).isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
        assertThat(completed.artifacts()).isNotEmpty();
        String callerResult = com.openjiuwen.service.app.controller.a2a.A2aPartContent.extractTaskResult(completed);
        assertThat(callerResult).contains("caller resumed:").contains(STUB_RESULT_TEXT);

        // the stub received exactly one SendMessage carrying the multimodal parts
        assertThat(requestCount.get()).isEqualTo(1);
        String captured = capturedBodies.peek();
        Map<String, Object> request = mapper.readValue(captured, Map.class);

        assertThat(request.get("jsonrpc")).isEqualTo("2.0");
        assertThat(request.get("method")).isEqualTo("SendMessage");

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) ((Map<?, ?>) request.get("params")).get("message");
        assertThat(message.get("role")).isEqualTo("ROLE_USER");
        assertThat(message.get("contextId")).isEqualTo("ctx-l2-stub");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) message.get("parts");
        assertThat(parts).hasSize(4);

        // part 0: flat TextPart, no kind member
        assertThat(parts.get(0)).containsKey("text").doesNotContainKey("kind");
        assertThat(parts.get(0).get("text")).isEqualTo("delegate to lowcode stub");

        // part 1: flat url file part — spec: url + name/mediaType as siblings, no kind/file wrapper
        Map<String, Object> urlPart = parts.get(1);
        assertThat(urlPart).doesNotContainKey("kind").doesNotContainKey("file");
        assertThat(urlPart.get("url")).isEqualTo("https://example.com/attachments/id-card.png");
        assertThat(urlPart.get("filename")).isEqualTo("id-card.png");
        assertThat(urlPart.get("mediaType")).isEqualTo("image/png");

        // part 2: flat raw file part — base64 payload intact
        Map<String, Object> rawPart = parts.get(2);
        assertThat(rawPart).doesNotContainKey("kind").doesNotContainKey("file");
        assertThat(rawPart.get("raw")).isEqualTo(RAW_BASE64);
        assertThat(new String(Base64.getDecoder().decode(assertInstanceOf(String.class, rawPart.get("raw"))),
                StandardCharsets.UTF_8)).isEqualTo(RAW_TEXT);
        assertThat(rawPart.get("filename")).isEqualTo("note.txt");
        assertThat(rawPart.get("mediaType")).isEqualTo("text/plain");

        // part 3: flat data part
        Map<String, Object> dataPart = parts.get(3);
        assertThat(dataPart).doesNotContainKey("kind");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) dataPart.get("data");
        assertThat(data.get("orderId")).isEqualTo("A-1024");
        assertThat(data.get("vip")).isEqualTo(true);
    }

    /**
     * FEAT-036 §2.4 step 5: transient remote failures (connection refused / 5xx) are
     * retried with exponential backoff (cap 3 retries); every replay re-sends the full
     * multimodal payload, and after the final success the caller task is resumed with
     * the stub result. Exhausted retries fall back to the existing failure surface
     * (covered by DualRuntimeFailureIntegrationTest).
     */
    @Test
    void remoteClientRetriesTransient5xxWithBackoffAndPartsPreserved() throws Exception {
        // keep the backoff base tiny for test speed (default is 200ms)
        java.lang.reflect.Field backoff = com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient.class
                .getDeclaredField("retryBackoffBaseMillis");
        backoff.setAccessible(true);
        backoff.setLong(remoteCaller, 10L);

        failFirstN.set(3);

        ResponseEntity<String> first = postA2a(rpc("SendMessage", "l2-stub-retry-start",
                Map.of("message", Map.of("role", "ROLE_USER", "messageId", "msg-l2-retry", "contextId", "ctx-l2-retry",
                        "parts", List.of(Map.of("text", "delegate to lowcode stub with retries"))))));

        Task completed = awaitCompletedTask(taskId(first));

        assertThat(completed.status().state()).isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
        assertThat(com.openjiuwen.service.app.controller.a2a.A2aPartContent.extractTaskResult(completed))
                .contains("caller resumed:").contains(STUB_RESULT_TEXT);

        // 1 original attempt + 3 retries (design cap), then success
        assertThat(requestCount.get()).isEqualTo(4);

        // every replay carries the identical full multimodal payload
        assertThat(capturedBodies).hasSize(4);
        for (String captured : capturedBodies) {
            Map<String, Object> request = mapper.readValue(captured, Map.class);
            assertThat(request.get("method")).isEqualTo("SendMessage");
            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) ((Map<?, ?>) request.get("params")).get("message");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> parts = (List<Map<String, Object>>) message.get("parts");
            assertThat(parts).hasSize(4);
            assertThat(parts.get(0).get("text")).isEqualTo("delegate to lowcode stub");
            assertThat(parts.get(1).get("url")).isEqualTo("https://example.com/attachments/id-card.png");
            assertThat(parts.get(2).get("raw")).isEqualTo(RAW_BASE64);
            assertThat(parts.get(2).get("filename")).isEqualTo("note.txt");
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) parts.get(3).get("data");
            assertThat(data.get("orderId")).isEqualTo("A-1024");
            assertThat(data.get("vip")).isEqualTo(true);
        }
    }

    /**
     * FEAT-036 §7.3 last row: when every retry is exhausted the caller task is resumed
     * with the REMOTE_UNAVAILABLE failure surface, and the original multimodal parts
     * survive in the task snapshot — the INPUT_REQUIRED status message keeps the
     * delegate interrupt payload (message + context parts) for resume/replay.
     *
     * @throws Exception when the exhaustion flow or task polling fails
     */
    @Test
    @SuppressWarnings("unchecked")
    void remoteClientExhaustsRetriesThenResumesWithUnavailable() throws Exception {
        // keep the DEFAULT backoff base (200ms) so the INPUT_REQUIRED window
        // (200+400+800ms of retries) is wide enough to observe the snapshot
        failFirstN.set(Integer.MAX_VALUE);

        ResponseEntity<String> first = postA2a(rpc("SendMessage", "l2-stub-exhaust-start",
                Map.of("message",
                        Map.of("role", "ROLE_USER", "messageId", "msg-l2-exhaust", "contextId", "ctx-l2-exhaust",
                                "parts", List.of(Map.of("text", "delegate to lowcode stub until exhausted"))))));
        String taskId = taskId(first);

        // §7.3: the original multimodal parts survive in the task snapshot — the FEAT-004
        // shadow task (`shadow:<agentId>:<parentTaskId>`, metadata `_remote_batch`) retains
        // `members[].parts` while the batch settles; the test CallerHandler captured it from
        // the resume call stack, before the normal post-resume cleanup deletes the shadow.
        Task completed = awaitCompletedTask(taskId);

        Map<String, Object> shadowSnapshot = CallerHandler.SHADOW_SNAPSHOTS.stream()
                .filter(snapshot -> snapshot.get("parentTaskId").equals(taskId)).findFirst().orElse(null);
        assertThat(shadowSnapshot).as("shadow task snapshot must exist at resume time").isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> shadowMembers = (List<Map<String, Object>>) shadowSnapshot.get("members");
        assertThat(shadowMembers).hasSize(1);
        Map<String, Object> failedMember = shadowMembers.get(0);
        assertThat(failedMember.get("toolCallId")).isEqualTo("call-lowcode");
        assertThat(failedMember.get("agentName")).isEqualTo("lowcode");
        assertThat(failedMember.get("state")).isEqualTo("FAILED");

        // the snapshotted parts preserve the original delegate payload verbatim
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> snapshottedParts = (List<Map<String, Object>>) failedMember.get("parts");
        assertThat(snapshottedParts).extracting(part -> part.get("kind")).containsExactly("url", "raw", "data");
        assertThat(snapshottedParts.get(0).get("url")).isEqualTo("https://example.com/attachments/id-card.png");
        assertThat(snapshottedParts.get(1).get("bytesBase64")).isEqualTo(RAW_BASE64);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) snapshottedParts.get(2).get("data");
        assertThat(data.get("orderId")).isEqualTo("A-1024");
        assertThat(data.get("vip")).isEqualTo(true);

        // the settled snapshot already carries the FEAT-004 failure回填 result
        @SuppressWarnings("unchecked")
        Map<String, Object> failureResult = (Map<String, Object>) failedMember.get("result");
        assertThat(failureResult.get("ok")).isEqualTo(false);
        assertThat(failureResult.get("code")).isEqualTo("REMOTE_UNAVAILABLE");
        assertThat(String.valueOf(failureResult.get("message"))).contains("500");

        // FEAT-004 §3.4: after a normal (failure) resume the READY shadow is deleted
        assertThat(taskStore.list(org.a2aproject.sdk.spec.ListTasksParams.builder().contextId("ctx-l2-exhaust").build())
                .tasks()).noneMatch(task -> task.id() != null && task.id().startsWith("shadow:"));

        // caller resumed through the degradation path, not the stub result
        assertThat(completed.status().state()).isEqualTo(org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED);
        assertThat(com.openjiuwen.service.app.controller.a2a.A2aPartContent.extractTaskResult(completed))
                .contains("caller resumed:").contains("REMOTE_UNAVAILABLE").contains("HTTP 500");
        assertThat(com.openjiuwen.service.app.controller.a2a.A2aPartContent.extractTaskResult(completed))
                .doesNotContain(STUB_RESULT_TEXT);

        // 1 original attempt + 3 retries (design cap), all rejected with 500
        assertThat(requestCount.get()).isEqualTo(4);
        assertThat(capturedBodies).hasSize(4);
    }

    /**
     * Hand-written 1.0.0 stub endpoint. The response templates below are authored from
     * the specification (flat parts, member-name discriminators, SCREAMING_SNAKE_CASE
     * states, {@code result.task} wrapper) and must not be produced via our SDK.
     */
    private void handleStub(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equals(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            capturedBodies.add(body);
            int sequence = requestCount.incrementAndGet();

            if (sequence <= failFirstN.get()) {
                exchange.sendResponseHeaders(500, -1);
                return;
            }

            Map<?, ?> request = mapper.readValue(body, Map.class);
            Object id = request.get("id");
            String method = String.valueOf(request.get("method"));
            String response;
            if ("SendMessage".equals(method)) {
                response = "{\"jsonrpc\":\"2.0\",\"id\":" + mapper.writeValueAsString(id)
                        + ",\"result\":{\"task\":{\"id\":\"stub-task-lowcode-1\","
                        + "\"contextId\":\"stub-ctx-lowcode\","
                        + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\",\"timestamp\":\"2026-08-29T00:00:00Z\"},"
                        + "\"artifacts\":[{\"artifactId\":\"stub-artifact-1\"," + "\"parts\":[{\"text\":\""
                        + STUB_RESULT_TEXT + "\"}]}]," + "\"history\":[]}}}";
            } else if ("GetTask".equals(method)) {
                response = "{\"jsonrpc\":\"2.0\",\"id\":" + mapper.writeValueAsString(id)
                        + ",\"result\":{\"id\":\"stub-task-lowcode-1\"," + "\"contextId\":\"stub-ctx-lowcode\","
                        + "\"status\":{\"state\":\"TASK_STATE_COMPLETED\",\"timestamp\":\"2026-08-29T00:00:00Z\"},"
                        + "\"artifacts\":[{\"artifactId\":\"stub-artifact-1\"," + "\"parts\":[{\"text\":\""
                        + STUB_RESULT_TEXT + "\"}]}]," + "\"history\":[]}}";
            } else {
                response = "{\"jsonrpc\":\"2.0\",\"id\":" + mapper.writeValueAsString(id)
                        + ",\"error\":{\"code\":-32601,\"message\":\"Method not found: " + method + "\"}}";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    private ResponseEntity<String> postA2a(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity("/a2a/", new HttpEntity<>(toJson(body), headers), String.class);
    }

    private String toJson(Map<String, Object> body) {
        try {
            return mapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private String taskId(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = mapper.readValue(response.getBody(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) body.get("result");
        assertThat(result).as("SendMessage must return a task").isNotNull();
        if (result.containsKey("task")) {
            return String.valueOf(((Map<?, ?>) result.get("task")).get("id"));
        }
        return String.valueOf(result.get("id"));
    }

    private Task awaitCompletedTask(String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + 20_000;
        String lastObserved = "<missing>";
        while (System.currentTimeMillis() < deadline) {
            Task task = taskStore.get(taskId);
            if (task != null && task.status() != null
                    && task.status().state() == org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED) {
                return task;
            }
            lastObserved = task == null || task.status() == null ? "<missing>" : String.valueOf(task.status().state());
            Thread.sleep(100);
        }
        throw new AssertionError("parent task did not complete: " + taskId + ", lastObserved=" + lastObserved);
    }

    private static Map<String, Object> rpc(String method, Object id, Map<String, Object> params) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private static AgentCard card(int port) {
        String url = "http://127.0.0.1:" + port + "/a2a";
        return AgentCard.builder().name("lowcode").description("lowcode stub").provider(new AgentProvider("", ""))
                .version("1.0").capabilities(new AgentCapabilities(false, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .securitySchemes(Collections.emptyMap()).securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", url, null, "1.0"))).url(url)
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @ComponentScan(basePackages = {"com.openjiuwen.service.app.controller", "com.openjiuwen.service.app.lifecycle"})
    static class CallerRuntimeApplication {
        @Bean
        @Primary
        AgentHandler callerHandler() {
            return new CallerHandler();
        }
    }

    /**
     * Raises an a2a_delegate interrupt whose context carries tool attachments as
     * normalized parts (design FEAT-036 §4.5/§5.2), then resumes with the stub result.
     */
    private static final class CallerHandler implements AgentHandler {
        /** Shadow-task snapshots captured on the resume call stack (best effort, diagnostics). */
        static final ConcurrentLinkedQueue<Map<String, Object>> SHADOW_SNAPSHOTS = new ConcurrentLinkedQueue<>();

        static volatile org.a2aproject.sdk.server.tasks.TaskStore handlerTaskStore;

        @Override
        public QueryResponse query(ServeRequest request) {
            Object results = request.getMetadata().get("runtime.remoteToolResults");
            if (results instanceof Map<?, ?> remoteResults) {
                captureShadowSnapshot(request);
                return response(request, "caller resumed:" + remoteResults.get("call-lowcode"));
            }
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("_interrupt_kind", "a2a_delegate");
            context.put("agentName", "lowcode");
            List<Map<String, Object>> attachments = new ArrayList<>();
            attachments.add(Map.of("kind", "url", "url", "https://example.com/attachments/id-card.png", "filename",
                    "id-card.png", "mediaType", "image/png"));
            attachments.add(Map.of("kind", "raw", "bytesBase64", RAW_BASE64, "filename", "note.txt", "mediaType",
                    "text/plain"));
            attachments.add(Map.of("kind", "data", "data", Map.of("orderId", "A-1024", "vip", true)));
            context.put("parts", attachments);
            return new QueryResponse(
                    Map.of("role", "assistant", "_interrupt",
                            Map.of("batchId", "l2-stub-batch", "items",
                                    List.of(Map.of("index", 0, "toolCallId", "call-lowcode", "toolName", "lowcode-tool",
                                            "message", "delegate to lowcode stub", "context", context)))),
                    request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            observer.onNext(new com.openjiuwen.service.spec.dto.QueryChunk(
                    com.openjiuwen.service.spec.dto.QueryChunk.TYPE_CHUNK, "unused"));
            observer.onComplete();
        }

        /**
         * Captures the FEAT-004 shadow task snapshot (`_remote_batch` metadata) while the
         * resume is still in flight — the caller handler runs on the resume call stack,
         * before the coordinator deletes the settled READY shadow.
         *
         * @param request the in-flight resume request whose conversation is inspected
         */
        private static void captureShadowSnapshot(ServeRequest request) {
            org.a2aproject.sdk.server.tasks.TaskStore store = handlerTaskStore;
            if (store == null) {
                return;
            }
            try {
                for (Task candidate : store.list(org.a2aproject.sdk.spec.ListTasksParams.builder()
                        .contextId(request.getConversationId()).build()).tasks()) {
                    if (candidate.id() != null && candidate.id().startsWith("shadow:") && candidate.metadata() != null
                            && candidate.metadata().get("_remote_batch") instanceof Map<?, ?> batch) {
                        Map<String, Object> copy = new LinkedHashMap<>();
                        batch.forEach((key, value) -> copy.put(String.valueOf(key), value));
                        SHADOW_SNAPSHOTS.add(copy);
                    }
                }
            } catch (IllegalStateException | IllegalArgumentException | UnsupportedOperationException ignored) {
                // best-effort diagnostics capture; the exhaustion test asserts on the result
            }
        }
    }

    private static QueryResponse response(ServeRequest request, String content) {
        return new QueryResponse(Map.of("role", "assistant", "content", content), request.getConversationId());
    }
}
