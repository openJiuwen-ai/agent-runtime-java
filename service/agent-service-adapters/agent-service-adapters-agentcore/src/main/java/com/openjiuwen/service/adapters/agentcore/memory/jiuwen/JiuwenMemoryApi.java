/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.jiuwen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.common.external.ExternalCallExecutor;
import com.openjiuwen.service.adapters.common.external.ExternalCallPolicy;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterException;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Governance-decorated HTTP client for the Jiuwen Memory Engine API.
 *
 * <p>Every HTTP call is executed through {@link ExternalCallExecutor}, so the runtime
 * timeout/retry/circuit-breaker/audit policy applies uniformly.
 *
 * @since 0.1.0
 */
public class JiuwenMemoryApi {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OP = "memory";

    private final ExternalCallExecutor executor;

    private final HttpClient httpClient;

    private final Duration requestTimeout;

    private final boolean shouldRetry;

    private final String apiKey;

    /**
     * Creates a governed Jiuwen Memory Engine transport.
     *
     * @param targetId audit/circuit target label (for example the endpoint)
     * @param policy external call policy (timeout/retry/circuit/audit)
     * @param apiKey API key used for Bearer authentication
     */
    public JiuwenMemoryApi(String targetId, ExternalCallPolicy policy, String apiKey) {
        this.executor = new ExternalCallExecutor("Memory", targetId, policy,
            ExternalSvcAdapterErrorCode.MEMORY_OUTBOUND_CALL_FAILED,
            ExternalSvcAdapterErrorCode.MEMORY_CIRCUIT_OPEN,
            ExternalSvcAdapterErrorCode.MEMORY_RETRY_INTERRUPTED,
            ExternalSvcAdapterErrorCode.MEMORY_TIMEOUT);
        int timeoutMs = policy != null ? policy.getTimeoutMs() : 30000;
        this.requestTimeout = Duration.ofMillis(Math.max(1, timeoutMs));
        this.shouldRetry = policy != null && policy.getRetry() != null && policy.getRetry().getMax() > 0;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(requestTimeout)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.apiKey = apiKey != null ? apiKey : "";
    }

    /**
     * Adds messages to the Memory Engine.
     *
     * @param baseUrl Memory Engine base URL
     * @param messages list of message maps (each with "role" and "content")
     * @param userId user ID
     * @param scopeId scope ID
     * @return raw response from the service
     */
    public Map<String, Object> addMessages(String baseUrl, List<Map<String, Object>> messages,
        String userId, String scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("messages", messages);
        if (userId != null && !userId.isBlank()) {
            body.put("user_id", userId);
        }
        if (scopeId != null && !scopeId.isBlank()) {
            body.put("scope_id", scopeId);
        }
        return executor.execute(OP, "add", shouldRetry,
            () -> send(baseUrl, "/add_messages/", "POST", body));
    }

    /**
     * Searches memories by semantic query.
     *
     * @param baseUrl Memory Engine base URL
     * @param query semantic search query
     * @param num maximum number of results
     * @param userId user ID
     * @param scopeId scope ID
     * @return list of memory records
     */
    public List<Map<String, Object>> searchMemory(String baseUrl, String query, int num,
        String userId, String scopeId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("num", num);
        if (userId != null && !userId.isBlank()) {
            body.put("user_id", userId);
        }
        if (scopeId != null && !scopeId.isBlank()) {
            body.put("scope_id", scopeId);
        }
        Map<String, Object> response = executor.execute(OP, "search", shouldRetry,
            () -> send(baseUrl, "/search_memory/", "POST", body));
        return extractResults(response);
    }

    /**
     * Gets user memories by page.
     *
     * @param baseUrl Memory Engine base URL
     * @param userId user ID
     * @param scopeId scope ID
     * @param pageSize page size
     * @param pageIdx page index (1-based)
     * @return list of memory records
     */
    public List<Map<String, Object>> getUserMemByPage(String baseUrl, String userId, String scopeId,
        int pageSize, int pageIdx) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (userId != null && !userId.isBlank()) {
            body.put("user_id", userId);
        }
        if (scopeId != null && !scopeId.isBlank()) {
            body.put("scope_id", scopeId);
        }
        body.put("page_size", pageSize);
        body.put("page_idx", pageIdx);
        Map<String, Object> response = executor.execute(OP, "getByPage", shouldRetry,
            () -> send(baseUrl, "/get_user_mem_by_page/", "POST", body));
        return extractResults(response);
    }

    /**
     * Checks whether the Memory Engine is healthy.
     *
     * @param baseUrl Memory Engine base URL
     * @return true if the service responds with a healthy status
     */
    public boolean isHealthy(String baseUrl) {
        try {
            Map<String, Object> response = executor.execute(OP, "health", false,
                () -> send(baseUrl, "/health", "GET", null));
            Object status = response.get("status");
            return "healthy".equalsIgnoreCase(String.valueOf(status));
        } catch (ExternalSvcAdapterException e) {
            return false;
        }
    }

    private Map<String, Object> send(String baseUrl, String path, String method,
        Map<String, Object> body) throws Exception {
        String normalizedBase = (baseUrl == null || baseUrl.isBlank())
            ? "http://localhost:8516"
            : baseUrl.replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(normalizedBase + path))
            .timeout(requestTimeout)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + apiKey);
        if (body != null) {
            String requestBody = MAPPER.writeValueAsString(body);
            builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> response = httpClient.send(builder.build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                "Jiuwen Memory API request failed with status " + response.statusCode() + ": " + response.body());
        }
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return new LinkedHashMap<>();
        }
        Object parsed = MAPPER.readValue(responseBody, Object.class);
        if (parsed instanceof Map<?, ?> map) {
            return castMap(map);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("results", parsed);
        return wrapped;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractResults(Map<String, Object> response) {
        Object results = response != null ? response.get("results") : null;
        if (results instanceof List<?> list) {
            List<Map<String, Object>> typed = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    typed.add(castMap(map));
                }
            }
            return typed;
        }
        return List.of();
    }

    private static Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
