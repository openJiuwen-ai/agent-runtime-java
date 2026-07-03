/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
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
 * WebFlux {@code /v1/query/reactive} integration tests against
 * {@link TestServiceApplication}.
 *
 * @since 0.1.0
 */
@SpringBootTest(classes = TestServiceApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {"spring.main.web-application-type=reactive",
        "openjiuwen.service.query.webflux.enabled=true"})
class QueryWebFluxIntegrationTest {
    @Autowired
    private WebTestClient webTestClient;

    private final ObjectMapper mapper = new ObjectMapper();

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

    @Test
    void streamingQueryReturnsPythonStyleSseChunk() {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("flux")), "conversation_id", "c-flux",
                "stream", true);

        byte[] responseBody = webTestClient.post().uri("/v1/query/reactive").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isOk().expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).expectBody().returnResult().getResponseBody();

        String text = responseText(responseBody);
        assertThat(text).contains("data: {");
        assertThat(text).contains("\"role\":\"assistant\"");
        assertThat(text).contains("turn1:flux");
    }

    @Test
    void streamDefaultsToSseWhenOmitted() {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("flux-default")), "conversation_id",
                "c-flux-default");

        byte[] responseBody = webTestClient.post().uri("/v1/query/reactive").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isOk().expectHeader()
                .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM).expectBody().returnResult().getResponseBody();

        String text = responseText(responseBody);
        assertThat(text).contains("data: {");
        assertThat(text).contains("turn1:flux-default");
    }

    @Test
    void concurrentStreamingQueriesUseIndependentConversationContext() throws Exception {
        int concurrency = 3;
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        ThreadPoolExecutor executor = fixedTestExecutor("query-flux-concurrent", concurrency, uncaught);
        try {
            List<CompletableFuture<String>> futures = IntStream.range(0, concurrency)
                    .mapToObj(index -> CompletableFuture.supplyAsync(
                            () -> streamText("c-flux-concurrent-" + index, "flux-concurrent-" + index), executor))
                    .toList();

            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();

            for (int index = 0; index < futures.size(); index++) {
                String text = futures.get(index).join();
                assertThat(text).contains("\"conversation_id\":\"c-flux-concurrent-" + index + "\"");
                assertThat(text).contains("turn1:flux-concurrent-" + index);
            }
            assertThat(uncaught.get()).isNull();
        } finally {
            shutdownExecutor(executor);
        }
    }

    private String streamText(String conversationId, String content) {
        byte[] responseBody = webTestClient.post().uri("/v1/query/reactive").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("messages", List.of(userMessage(content)), "conversation_id", conversationId,
                        "stream", true))
                .exchange().expectStatus().isOk().expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody().returnResult().getResponseBody();
        return responseText(responseBody);
    }

    private static String responseText(byte[] responseBody) {
        assertThat(responseBody).isNotNull();
        return new String(responseBody, StandardCharsets.UTF_8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonStreamingQueryReturnsAggregatedJson() throws Exception {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("json")), "conversation_id", "c-flux-json",
                "stream", false);

        byte[] bytes = webTestClient.post().uri("/v1/query/reactive").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isOk().expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON).expectBody().returnResult().getResponseBody();

        Map<String, Object> json = mapper.readValue(bytes, Map.class);
        Map<String, Object> result = (Map<String, Object>) json.get("result");
        assertThat(result.get("content")).isEqualTo("turn1:json");
        assertThat(result).doesNotContainKey("events");
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingConversationIdReturnsBadRequestBody() throws Exception {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("missing")), "stream", false);

        byte[] bytes = webTestClient.post().uri("/v1/query/reactive").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isBadRequest().expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON).expectBody().returnResult().getResponseBody();

        Map<String, Object> json = mapper.readValue(bytes, Map.class);
        assertThat(json).containsEntry("type", "error");
        assertThat(json).containsEntry("error", "conversation_id is required");
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankConversationIdReturnsBadRequestBody() throws Exception {
        Map<String, Object> body = Map.of("messages", List.of(userMessage("blank")), "conversation_id", " ", "stream",
                false);

        byte[] bytes = webTestClient.post().uri("/v1/query/reactive").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body).exchange().expectStatus().isBadRequest().expectHeader()
                .contentTypeCompatibleWith(MediaType.APPLICATION_JSON).expectBody().returnResult().getResponseBody();

        Map<String, Object> json = mapper.readValue(bytes, Map.class);
        assertThat(json).containsEntry("type", "error");
        assertThat(json).containsEntry("error", "conversation_id is required");
    }
}
