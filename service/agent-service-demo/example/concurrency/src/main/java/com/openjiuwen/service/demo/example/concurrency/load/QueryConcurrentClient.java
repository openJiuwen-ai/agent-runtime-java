/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * HTTP client for concurrent {@code /v1/query} load against the concurrency demo service.
 *
 * @since 0.1.0
 */
public final class QueryConcurrentClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;

    private final URI baseUri;

    private final Duration timeout;

    /**
     * Creates a client with a bounded async executor for concurrent SSE calls.
     *
     * @param baseUrl service root URL (without trailing slash)
     * @param timeout per-request timeout
     */
    public QueryConcurrentClient(String baseUrl, Duration timeout) {
        this.baseUri = URI.create(trimTrailingSlash(baseUrl));
        this.timeout = timeout;
        int workers = Math.max(4, Runtime.getRuntime().availableProcessors() * 4);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .executor(Executors.newFixedThreadPool(workers, runnable -> {
                Thread thread = new Thread(runnable, "concurrency-http-client");
                thread.setDaemon(true);
                return thread;
            }))
            .build();
    }

    /**
     * Invokes the {@code skill_echo} scenario through {@code /v1/query}.
     *
     * @param conversationId stable conversation identifier
     * @param token echo token expected in the response
     * @param stream whether to use SSE streaming
     * @return request result with latency and content or error
     */
    public QueryResult skillEcho(String conversationId, String token, boolean stream) {
        return query(conversationId, "skill_echo:" + token, stream, token);
    }

    /**
     * Invokes the {@code concurrent_lookup} scenario through {@code /v1/query}.
     *
     * @param conversationId stable conversation identifier
     * @param key lookup key expected in the response
     * @param delayMs simulated lookup delay passed to the agent
     * @param stream whether to use SSE streaming
     * @return request result with latency and content or error
     */
    public QueryResult lookup(String conversationId, String key, int delayMs, boolean stream) {
        return query(conversationId, "lookup:" + key + " delayMs=" + delayMs, stream, key);
    }

    /**
     * Sends a generic query request and optionally validates a response marker.
     *
     * @param conversationId stable conversation identifier
     * @param message user message body
     * @param stream whether to use SSE streaming
     * @param expectedMarker substring that must appear in the response content
     * @return request result with latency and content or error
     */
    public QueryResult query(String conversationId, String message, boolean stream, String expectedMarker) {
        long started = System.nanoTime();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("conversation_id", conversationId);
            body.put("message", message);
            body.put("stream", stream);
            String json = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder().uri(baseUri.resolve("/v1/query"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = elapsedMs(started);
            if (response.statusCode() != 200) {
                return QueryResult.failure(latencyMs, "HTTP " + response.statusCode() + ": " + response.body());
            }
            String content = extractContent(response.body(), stream);
            if (expectedMarker != null && !expectedMarker.isBlank() && !content.contains(expectedMarker)) {
                return QueryResult.failure(latencyMs,
                    "missing marker '" + expectedMarker + "' in response: " + abbreviate(content));
            }
            return QueryResult.success(latencyMs, content);
        } catch (Exception ex) {
            return QueryResult.failure(elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * Probes {@code GET /health} on the configured base URL.
     *
     * @return {@code true} when HTTP 200 is returned
     */
    public boolean healthReady() {
        try {
            HttpRequest request = HttpRequest.newBuilder().uri(baseUri.resolve("/health")).timeout(timeout).GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception ex) {
            return false;
        }
    }

    private static String extractContent(String body, boolean stream) throws Exception {
        if (!stream) {
            JsonNode root = MAPPER.readTree(body);
            JsonNode content = root.path("result").path("content");
            return content.isTextual() ? content.asText() : content.toString();
        }
        StringBuilder aggregated = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String payload = line.substring("data:".length()).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                continue;
            }
            JsonNode event = MAPPER.readTree(payload);
            JsonNode delta = event.path("result").path("content");
            if (delta.isTextual()) {
                aggregated.append(delta.asText());
            }
            JsonNode full = event.path("result").path("full_content");
            if (full.isTextual() && !full.asText().isBlank()) {
                aggregated.setLength(0);
                aggregated.append(full.asText());
            }
        }
        if (aggregated.length() > 0) {
            return aggregated.toString();
        }
        return body;
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String trimTrailingSlash(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            return baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 240 ? value : value.substring(0, 240) + "...";
    }

    /**
     * Result of a single query HTTP call.
     *
     * @param success whether the call succeeded
     * @param latencyMs observed latency in milliseconds
     * @param content extracted agent response content on success
     * @param error error message on failure
     */
    public record QueryResult(boolean success, long latencyMs, String content, String error) {
        /**
         * Builds a successful result.
         *
         * @param latencyMs observed latency in milliseconds
         * @param content extracted response content
         * @return success result
         */
        static QueryResult success(long latencyMs, String content) {
            return new QueryResult(true, latencyMs, content, null);
        }

        /**
         * Builds a failed result.
         *
         * @param latencyMs observed latency in milliseconds
         * @param error error message
         * @return failure result
         */
        static QueryResult failure(long latencyMs, String error) {
            return new QueryResult(false, latencyMs, null, error);
        }
    }
}
