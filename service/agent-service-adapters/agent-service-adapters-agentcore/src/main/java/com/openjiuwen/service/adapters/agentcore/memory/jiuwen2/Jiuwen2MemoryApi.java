/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.jiuwen2;

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
import java.util.Optional;

/**
 * Governance-decorated HTTP client for the agent-memory 2.0 API surface.
 *
 * <p>The 2.0 surface exposes every {@code MemoryAPI} method as
 * {@code POST /v1/<method>} plus {@code GET /healthz}; request fields are strict
 * (unknown fields are rejected with 400) and the caller identity comes from the
 * {@code Authorization: Bearer} credential, never from the payload.
 *
 * <p>Every call is executed through {@link ExternalCallExecutor}, so the runtime
 * timeout/retry/circuit-breaker/audit policy applies uniformly.
 *
 * @since 0.1.0
 */
public class Jiuwen2MemoryApi {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OP = "memory";

    private final ExternalCallExecutor executor;

    private final HttpClient httpClient;

    private final Duration requestTimeout;

    private final boolean shouldRetry;

    private final String apiKey;

    /**
     * Creates a governed agent-memory 2.0 transport.
     *
     * @param targetId audit/circuit target label (for example the endpoint)
     * @param policy external call policy (timeout/retry/circuit/audit)
     * @param apiKey API key used for Bearer authentication
     */
    public Jiuwen2MemoryApi(String targetId, ExternalCallPolicy policy, String apiKey) {
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
     * Adds one memory unit.
     *
     * @param baseUrl agent-memory 2.0 base URL
     * @param content memory text
     * @param scope request scope ({@code org/space/user/agent/session})
     * @param userMetadata business metadata stored alongside the unit
     * @return inserted memory units as returned by the service
     */
    public List<Map<String, Object>> add(String baseUrl, String content, Map<String, Object> scope,
        Map<String, Object> userMetadata) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        body.put("scope", scope);
        if (userMetadata != null && !userMetadata.isEmpty()) {
            body.put("user_metadata", userMetadata);
        }
        return executeAdd(baseUrl, body);
    }

    /**
     * Searches memories by semantic query.
     *
     * @param baseUrl agent-memory 2.0 base URL
     * @param query semantic search query
     * @param scope request scope used as the search context scope
     * @param topK maximum number of results
     * @return matched result items ({@code unit_id/score/content/...})
     */
    public List<Map<String, Object>> search(String baseUrl, String query, Map<String, Object> scope,
        int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("scope", scope);
        body.put("context", context);
        body.put("top_k", topK);
        // agent-memory 2.0 defaults to disclosure l0 (abstract only); full text requires
        // an explicit l2, otherwise returned content is empty.
        body.put("disclosure", "l2");
        Map<String, Object> response = executor.execute(OP, "search", shouldRetry,
            () -> send(baseUrl, "/v1/search", "POST", body, false)).orElse(Map.of());
        return extractItems(response, "items");
    }

    /**
     * Fetches one memory unit by id.
     *
     * @param baseUrl agent-memory 2.0 base URL
     * @param unitId memory unit id
     * @param scope request scope
     * @return the memory unit, or empty when the id does not exist
     */
    public Optional<Map<String, Object>> get(String baseUrl, String unitId, Map<String, Object> scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unit_id", unitId);
        body.put("scope", scope);
        return executor.execute(OP, "get", shouldRetry,
            () -> send(baseUrl, "/v1/get", "POST", body, true));
    }

    /**
     * Deletes memory units by id.
     *
     * @param baseUrl agent-memory 2.0 base URL
     * @param unitId memory unit id to delete
     * @param scope request scope limiting the delete
     * @return ids of the deleted memory units
     */
    public List<String> delete(String baseUrl, String unitId, Map<String, Object> scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> selector = new LinkedHashMap<>();
        selector.put("unit_ids", List.of(unitId));
        selector.put("scope", scope);
        body.put("selector", selector);
        Map<String, Object> response = executor.execute(OP, "delete", shouldRetry,
            () -> send(baseUrl, "/v1/delete", "POST", body, false)).orElse(Map.of());
        return extractIds(response);
    }

    /**
     * Checks whether the memory service is healthy.
     *
     * @param baseUrl agent-memory 2.0 base URL
     * @return true if the service responds with a healthy status
     */
    public boolean isHealthy(String baseUrl) {
        try {
            Map<String, Object> response = executor.execute(OP, "health", false,
                () -> send(baseUrl, "/healthz", "GET", null, false)).orElse(Map.of());
            return "ok".equalsIgnoreCase(stringValue(response.get("status")));
        } catch (ExternalSvcAdapterException e) {
            return false;
        }
    }

    private List<Map<String, Object>> executeAdd(String baseUrl, Map<String, Object> body) {
        Map<String, Object> response = executor.execute(OP, "add", shouldRetry,
            () -> send(baseUrl, "/v1/add", "POST", body, false)).orElse(Map.of());
        return extractItems(response, "units");
    }

    private static List<Map<String, Object>> extractItems(Map<String, Object> response, String listField) {
        if (response == null) {
            return List.of();
        }
        if (response.containsKey(listField)) {
            return wrapList(response.get(listField));
        }
        // /v1/add returns the unit array as the top-level JSON value; the dispatcher
        // wraps bare arrays under "results".
        return wrapList(response.get("results"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> wrapList(Object raw) {
        if (raw instanceof List<?> list) {
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

    /**
     * Extracts the string ids returned by {@code /v1/delete}. The endpoint answers with
     * a bare JSON array of ids, which the dispatcher wraps under {@code results}.
     *
     * @param response the delete response body
     * @return the ids of the deleted memory units, empty when none are returned
     */
    private static List<String> extractIds(Map<String, Object> response) {
        if (response == null) {
            return List.of();
        }
        Object raw = response.get("results");
        if (raw instanceof List<?> list) {
            List<String> ids = new ArrayList<>();
            for (Object item : list) {
                if (item != null && !(item instanceof Map<?, ?>)) {
                    ids.add(String.valueOf(item));
                }
            }
            return ids;
        }
        return List.of();
    }

    private Optional<Map<String, Object>> send(String baseUrl, String path, String method,
        Map<String, Object> body, boolean canBeAbsent) throws Exception {
        String normalizedBase = (baseUrl == null || baseUrl.isBlank())
            ? "http://localhost:8137"
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
            if (canBeAbsent && response.statusCode() == 404) {
                return Optional.empty();
            }
            throw new IllegalStateException(
                "Jiuwen2 Memory API request failed with status " + response.statusCode() + ": " + response.body());
        }
        String responseBody = response.body();
        if (responseBody == null || responseBody.isBlank()) {
            return Optional.of(new LinkedHashMap<>());
        }
        Object parsed = MAPPER.readValue(responseBody, Object.class);
        if (parsed instanceof Map<?, ?> map) {
            return Optional.of(castMap(map));
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("results", parsed);
        return Optional.of(wrapped);
    }

    private static Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }
}
