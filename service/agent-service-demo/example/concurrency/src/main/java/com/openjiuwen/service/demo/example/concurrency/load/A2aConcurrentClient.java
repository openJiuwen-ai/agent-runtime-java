/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.load;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP client for concurrent A2A JSON-RPC load against Agent A (or any A2A endpoint).
 *
 * @since 0.1.0
 */
public final class A2aConcurrentClient {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient httpClient;
    private final URI baseUri;
    private final Duration timeout;

    /**
     * Creates a client targeting the given A2A base URL.
     *
     * @param baseUrl service root URL (without trailing slash)
     * @param timeout per-request timeout
     */
    public A2aConcurrentClient(String baseUrl, Duration timeout) {
        this.baseUri = URI.create(trimTrailingSlash(baseUrl));
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    /**
     * Sends a non-streaming {@code SendMessage} JSON-RPC request.
     *
     * @param contextId A2A conversation/context identifier
     * @param text user message text
     * @return request result with latency and payload or error
     */
    public A2aResult sendMessage(String contextId, String text) {
        return invoke("SendMessage", contextId, text, false);
    }

    /**
     * Sends a streaming {@code SendStreamingMessage} JSON-RPC request.
     *
     * @param contextId A2A conversation/context identifier
     * @param text user message text
     * @return request result with latency and aggregated SSE payload or error
     */
    public A2aResult sendStreamingMessage(String contextId, String text) {
        return invoke("SendStreamingMessage", contextId, text, true);
    }

    private A2aResult invoke(String method, String contextId, String text, boolean streaming) {
        long started = System.nanoTime();
        try {
            Map<String, Object> params = Map.of("message", Map.of("role", "ROLE_USER", "parts",
                List.of(Map.of("text", text)), "contextId", contextId));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("jsonrpc", "2.0");
            body.put("id", UUID.randomUUID().toString());
            body.put("method", method);
            body.put("params", params);
            String json = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder().uri(baseUri.resolve("/a2a/"))
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", streaming ? "text/event-stream" : "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long latencyMs = elapsedMs(started);
            if (response.statusCode() != 200) {
                return A2aResult.failure(latencyMs, "HTTP " + response.statusCode() + ": " + response.body());
            }
            String payload = streaming ? aggregateSse(response.body()) : response.body();
            if (payload.contains("\"error\"")) {
                return A2aResult.failure(latencyMs, abbreviate(payload));
            }
            return A2aResult.success(latencyMs, payload);
        } catch (Exception ex) {
            return A2aResult.failure(elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
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

    private static String aggregateSse(String body) {
        StringBuilder aggregated = new StringBuilder(body);
        for (String line : body.split("\\R")) {
            if (!line.startsWith("data:")) {
                continue;
            }
            aggregated.append('\n').append(line.substring("data:".length()).trim());
        }
        return aggregated.toString();
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
     * Result of a single A2A HTTP call.
     *
     * @param success whether the call succeeded
     * @param latencyMs observed latency in milliseconds
     * @param payload response body on success
     * @param error error message on failure
     */
    public record A2aResult(boolean success, long latencyMs, String payload, String error) {
        static A2aResult success(long latencyMs, String payload) {
            return new A2aResult(true, latencyMs, payload, null);
        }

        static A2aResult failure(long latencyMs, String error) {
            return new A2aResult(false, latencyMs, null, error);
        }
    }
}
