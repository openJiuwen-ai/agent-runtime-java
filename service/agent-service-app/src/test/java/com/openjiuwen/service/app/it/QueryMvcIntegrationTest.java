/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MVC {@code /v1/query} integration tests against
 * {@link TestServiceApplication}.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class QueryMvcIntegrationTest {
    @Autowired
    private TestRestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    private ResponseEntity<String> postQuery(String path, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postQuery(String path, Map<String, Object> body, HttpHeaders headers) {
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.postForEntity(path, new HttpEntity<>(body, headers), String.class);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSse(String body) {
        List<Map<String, Object>> events = new ArrayList<>();
        for (String line : body.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("data:")) {
                String json = trimmed.substring("data:".length()).strip();
                try {
                    events.add(mapper.readValue(json, Map.class));
                } catch (Exception e) {
                    throw new IllegalStateException("bad SSE data line: " + json, e);
                }
            }
        }
        return events;
    }

    private static Map<String, Object> userMessage(String content) {
        return Map.of("role", "user", "content", content);
    }

    private static ThreadPoolExecutor fixedTestExecutor(String threadNamePrefix, int size,
            AtomicReference<Throwable> uncaught) {
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(size), task -> {
            Thread thread = new Thread(task, threadNamePrefix + "-" + sequence.incrementAndGet());
            thread.setUncaughtExceptionHandler((unused, error) -> uncaught.compareAndSet(null, error));
            return thread;
        });
    }

    private static void shutdownExecutor(ThreadPoolExecutor executor) throws InterruptedException {
        executor.shutdown();
        if (!executor.awaitTermination(5L, TimeUnit.SECONDS)) {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5L, TimeUnit.SECONDS)).isTrue();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> responseJson(ResponseEntity<String> response) throws Exception {
        return mapper.readValue(response.getBody(), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(ResponseEntity<String> response) throws Exception {
        return (Map<String, Object>) responseJson(response).get("result");
    }

    @Test
    @Tag("smoke")
    void streamingQueryReturnsPythonStyleSseChunk() {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("hello")), "conversation_id", "c-stream",
                "stream", true);

        ResponseEntity<String> resp = postQuery("/v1/query", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(resp.getBody()).contains("data: {");

        List<Map<String, Object>> events = parseSse(resp.getBody());
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("role", "assistant");
        assertThat(events.get(0)).containsEntry("content", "turn1:hello");
        assertThat(events.get(0)).doesNotContainKey("events");
    }

    @Test
    void streamDefaultsToSseWhenOmitted() {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("default-stream")), "conversation_id",
                "c-default-stream");

        ResponseEntity<String> resp = postQuery("/v1/query", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);

        List<Map<String, Object>> events = parseSse(resp.getBody());
        assertThat(events).hasSize(1);
        assertThat(events.get(0)).containsEntry("content", "turn1:default-stream");
    }

    @Test
    @Tag("smoke")
    @SuppressWarnings("unchecked")
    void nonStreamingQueryReturnsAggregatedJson() throws Exception {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("hi")), "conversation_id", "c-json", "stream",
                false);

        ResponseEntity<String> resp = postQuery("/v1/query", body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getHeaders().getContentType().toString()).startsWith(MediaType.APPLICATION_JSON_VALUE);

        Map<String, Object> json = mapper.readValue(resp.getBody(), Map.class);
        assertThat(json).containsEntry("conversation_id", "c-json");
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result).containsEntry("role", "assistant");
        assertThat(result).containsEntry("content", "turn1:hi");
        assertThat(result).doesNotContainKey("events");
    }

    @Test
    void messagesTakePrecedenceOverMessageField() throws Exception {
        Map<String, Object> body = Map.of("message", "ignored", "messages", List.of(userMessage("real")),
                "conversation_id", "c-priority", "stream", false);

        Map<String, Object> result = result(postQuery("/v1/query", body));

        assertThat(result).containsEntry("content", "turn1:real");
        assertThat(result).containsEntry("messages_size", 1);
    }

    @Test
    void assistantOnlyMessagesFallBackToLastMessageContent() throws Exception {
        Map<String, Object> body = Map.of("messages", List.of(Map.of("role", "assistant", "content", "fallback")),
                "conversation_id", "c-fallback", "stream", false);

        Map<String, Object> result = result(postQuery("/v1/query", body));

        assertThat(result).containsEntry("content", "turn1:fallback");
        assertThat(result).containsEntry("messages_size", 1);
    }

    @Test
    void emptyMessageListProducesEmptyQuery() throws Exception {
        Map<String, Object> body = Map.of("conversation_id", "c-empty", "stream", false);

        Map<String, Object> result = result(postQuery("/v1/query", body));

        assertThat(result).containsEntry("content", "turn1:");
        assertThat(result).containsEntry("messages_size", 0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiTurnRetainsContextForSameConversation() throws Exception {
        Map<String, Object> turn1 = Map.of("messages", List.of(userMessage("a")), "conversation_id", "c-multi",
                "stream", false);
        Map<String, Object> turn2 = Map.of("messages", List.of(userMessage("b")), "conversation_id", "c-multi",
                "stream", false);

        Map<String, Object> r1 = mapper.readValue(postQuery("/v1/query", turn1).getBody(), Map.class);
        Map<String, Object> r2 = mapper.readValue(postQuery("/v1/query", turn2).getBody(), Map.class);

        Map<String, Object> result1 = (Map<String, Object>) r1.get("result");
        Map<String, Object> result2 = (Map<String, Object>) r2.get("result");
        assertThat(result1.get("content")).isEqualTo("turn1:a");
        assertThat(result2.get("content")).isEqualTo("turn2:b|prev=a");
    }

    @Test
    void differentConversationIdsKeepContextIsolated() throws Exception {
        Map<String, Object> conv1Turn1 = result(postQuery("/v1/query",
                Map.of("messages", List.of(userMessage("a1")), "conversation_id", "c-isolated-1", "stream", false)));
        Map<String, Object> conv2Turn1 = result(postQuery("/v1/query",
                Map.of("messages", List.of(userMessage("b1")), "conversation_id", "c-isolated-2", "stream", false)));
        Map<String, Object> conv1Turn2 = result(postQuery("/v1/query",
                Map.of("messages", List.of(userMessage("a2")), "conversation_id", "c-isolated-1", "stream", false)));
        Map<String, Object> conv2Turn2 = result(postQuery("/v1/query",
                Map.of("messages", List.of(userMessage("b2")), "conversation_id", "c-isolated-2", "stream", false)));

        assertThat(conv1Turn1).containsEntry("content", "turn1:a1");
        assertThat(conv2Turn1).containsEntry("content", "turn1:b1");
        assertThat(conv1Turn2).containsEntry("content", "turn2:a2|prev=a1");
        assertThat(conv2Turn2).containsEntry("content", "turn2:b2|prev=b1");
        assertThat(conv1Turn2.get("content")).asString().doesNotContain("b1");
        assertThat(conv2Turn2.get("content")).asString().doesNotContain("a1");
    }

    @Test
    void concurrentStreamingQueriesUseIndependentConversationContext() throws Exception {
        int concurrency = 3;
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        ThreadPoolExecutor executor = fixedTestExecutor("query-mvc-concurrent", concurrency, uncaught);
        try {
            List<CompletableFuture<Map<String, Object>>> futures = IntStream.range(0, concurrency)
                    .mapToObj(index -> CompletableFuture.supplyAsync(
                            () -> streamEvent("c-mvc-concurrent-" + index, "mvc-concurrent-" + index), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            for (int index = 0; index < futures.size(); index++) {
                Map<String, Object> event = futures.get(index).join();
                assertThat(event).containsEntry("conversation_id", "c-mvc-concurrent-" + index);
                assertThat(event).containsEntry("content", "turn1:mvc-concurrent-" + index);
            }
            assertThat(uncaught.get()).isNull();
        } finally {
            shutdownExecutor(executor);
        }
    }

    private Map<String, Object> streamEvent(String conversationId, String content) {
        ResponseEntity<String> response = postQuery("/v1/query",
                Map.of("messages", List.of(userMessage(content)), "conversation_id", conversationId, "stream", true));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);

        List<Map<String, Object>> events = parseSse(response.getBody());
        assertThat(events).hasSize(1);
        return events.get(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyPathWorks() throws Exception {
        Map<String, Object> body = Map.of("message", "legacy", "conversation_id", "c-legacy", "stream", false);

        Map<String, Object> json = mapper.readValue(postQuery("/query", body).getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result.get("content")).isEqualTo("turn1:legacy");
    }

    @Test
    @SuppressWarnings("unchecked")
    void tenantHeadersOverrideBodyFields() throws Exception {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("ctx")), "conversation_id", "c-context",
                "user_id", "body-user", "space_id", "body-space", "tenant_id", "body-tenant", "stream", false);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-ID", "header-user");
        headers.set("X-Space-ID", "header-space");
        headers.set("X-Tenant-ID", "header-tenant");

        Map<String, Object> json = mapper.readValue(postQuery("/v1/query", body, headers).getBody(), Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");

        assertThat(result).containsEntry("user_id", "header-user");
        assertThat(result).containsEntry("space_id", "header-space");
        assertThat(result).containsEntry("tenant_id", "header-tenant");
        assertThat(result).containsEntry("messages_size", 1);
    }

    @Test
    void blankTenantHeadersDoNotOverrideBodyFields() throws Exception {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("ctx")), "conversation_id", "c-blank-context",
                "user_id", "body-user", "space_id", "body-space", "tenant_id", "body-tenant", "stream", false);
        // SB 4 RestClient rejects whitespace-only header values; absent headers match
        // isBlank() semantics.
        HttpHeaders headers = new HttpHeaders();

        Map<String, Object> result = result(postQuery("/v1/query", body, headers));

        assertThat(result).containsEntry("user_id", "body-user");
        assertThat(result).containsEntry("space_id", "body-space");
        assertThat(result).containsEntry("tenant_id", "body-tenant");
    }

    @Test
    void missingConversationIdReturnsBadRequest() throws Exception {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("hi")), "stream", false);

        ResponseEntity<String> resp = postQuery("/v1/query", body);
        Map<String, Object> json = responseJson(resp);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json).containsEntry("type", "error");
        assertThat(json).containsEntry("error", "conversation_id is required");
    }

    @Test
    void blankConversationIdReturnsBadRequestBody() throws Exception {
        Map<String, Object> body = Map.of("conversation_id", " ", "messages", List.of(userMessage("hi")), "stream",
                false);

        ResponseEntity<String> resp = postQuery("/v1/query", body);
        Map<String, Object> json = responseJson(resp);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json).containsEntry("type", "error");
        assertThat(json).containsEntry("error", "conversation_id is required");
    }
}
