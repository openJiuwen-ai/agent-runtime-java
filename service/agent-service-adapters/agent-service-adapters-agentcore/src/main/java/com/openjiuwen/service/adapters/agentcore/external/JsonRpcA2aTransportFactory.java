/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.extensions.a2a.A2AClient;
import com.openjiuwen.extensions.a2a.A2ATransformer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JSON-RPC over HTTP transport for Core's {@link A2AClient}. Core ships the
 * client plumbing but leaves the wire transport to the embedding application,
 * so the Service layer supplies this default JSON-RPC implementation.
 *
 * <p>Requests use the {@code SendMessage} method with the A2A message payload;
 * responses are returned as JSON-RPC result maps which {@code A2ATransformer}
 * converts back to {@link com.openjiuwen.core.singleagent.schema.AgentResult}.
 *
 * @since 0.1.2
 */
public final class JsonRpcA2aTransportFactory implements A2AClient.A2AClientFactory {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String METHOD_SEND_MESSAGE = "SendMessage";

    private static final AtomicLong REQUEST_IDS = new AtomicLong();

    /** Endpoint URL taken from the card's first supported interface. */
    private volatile String endpoint;

    @Override
    public A2AClient.A2AClientTransport create(A2AClient.ClientConfig config, Object card) {
        if (endpoint == null && card instanceof com.openjiuwen.extensions.a2a.A2AAgentCardAdapter.A2aAgentCard a2aCard
                && !a2aCard.getSupportedInterfaces().isEmpty()) {
            endpoint = a2aCard.getSupportedInterfaces().get(0).getUrl();
        }
        HttpClient httpClient = config != null && config.getHttpClient() != null
            ? config.getHttpClient()
            : HttpClient.newHttpClient();
        Map<String, String> authHeaders = config != null && config.getAuthHeaders() != null
            ? config.getAuthHeaders()
            : Map.of();
        return new JsonRpcA2aTransport(httpClient, endpoint, authHeaders);
    }

    private static final class JsonRpcA2aTransport implements A2AClient.A2AClientTransport {
        private final HttpClient httpClient;

        private final String endpoint;

        private final Map<String, String> authHeaders;

        private JsonRpcA2aTransport(HttpClient httpClient, String endpoint, Map<String, String> authHeaders) {
            this.httpClient = httpClient;
            this.endpoint = endpoint;
            this.authHeaders = authHeaders == null ? Map.of() : authHeaders;
        }

        @Override
        public A2AClient.A2AEventStream sendMessage(
            com.openjiuwen.extensions.a2a.A2ATransformer.SendMessageRequest request) {
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalStateException("A2A endpoint URL is not configured on the agent card");
            }
            Map<String, Object> response = postJsonRpc(METHOD_SEND_MESSAGE,
                Map.of("message", A2ATransformer.fromA2aRequest(request)), null);
            Object result = response.get("result");
            return new SingleEventStream(result == null ? Map.of() : result);
        }

        @Override
        public CompletionStage<Object> cancelTask(A2AClient.CancelTaskRequest request) {
            return CompletableFuture.completedFuture(
                postJsonRpc("CancelTask", Map.of("id", request == null ? "" : request.getId()), null).get("result"));
        }

        @Override
        public CompletionStage<Void> close() {
            return CompletableFuture.completedFuture(null);
        }

        private Map<String, Object> postJsonRpc(String method, Map<String, Object> params, Duration timeout) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jsonrpc", "2.0");
            payload.put("id", nextRequestId());
            payload.put("method", method);
            payload.put("params", params);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .timeout(timeout != null ? timeout : Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(payload), StandardCharsets.UTF_8));
            authHeaders.forEach(builder::header);
            try {
                HttpResponse<String> response =
                    httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new IllegalStateException(
                        "A2A JSON-RPC call failed with status " + response.statusCode() + ": " + response.body());
                }
                return Json.read(response.body());
            } catch (InterruptedException error) {
                // Cooperative cancel propagation is handled by callers via a stop flag;
                // do not re-issue Thread.currentThread().interrupt() here.
                throw new IllegalStateException("A2A JSON-RPC call interrupted", error);
            } catch (java.io.IOException error) {
                throw new IllegalStateException("A2A JSON-RPC call failed: " + error.getMessage(), error);
            }
        }

        private static String nextRequestId() {
            return "svc-" + UUID.randomUUID() + "-" + REQUEST_IDS.incrementAndGet();
        }
    }

    /** Single-result stream produced by a synchronous JSON-RPC call. */
    private static final class SingleEventStream implements A2AClient.A2AEventStream {
        private final List<Object> events;

        private int cursor;

        private SingleEventStream(Object event) {
            this.events = List.of(event);
        }

        @Override
        public boolean hasNext() {
            return cursor < events.size();
        }

        @Override
        public Object next() {
            if (!hasNext()) {
                throw new java.util.NoSuchElementException();
            }
            return events.get(cursor++);
        }

        @Override
        public void close() {
            cursor = events.size();
        }
    }

    /** Minimal JSON helpers kept local to avoid extra mapper configuration. */
    private static final class Json {
        private Json() {
        }

        private static String write(Object value) {
            try {
                return MAPPER.writeValueAsString(value);
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("Failed to serialize A2A JSON-RPC request", error);
            }
        }

        private static Map<String, Object> read(String value) {
            try {
                return MAPPER.readValue(value,
                    MAPPER.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
            } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                throw new IllegalStateException("Failed to parse A2A JSON-RPC response", error);
            }
        }
    }
}
