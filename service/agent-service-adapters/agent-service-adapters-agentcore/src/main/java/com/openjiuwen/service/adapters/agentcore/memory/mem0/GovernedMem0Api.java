/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.mem0;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.common.external.ExternalCallExecutor;
import com.openjiuwen.service.adapters.common.external.ExternalCallPolicy;
import com.openjiuwen.service.adapters.common.external.ExternalSvcAdapterErrorCode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Governance-decorated mem0 HTTP client.
 *
 * <p>Every mem0 HTTP call is executed through {@link ExternalCallExecutor}, so the runtime
 * timeout/retry/circuit-breaker/audit policy applies uniformly. The underlying
 * {@link HttpClient}/{@link HttpRequest} timeout is aligned to the policy timeout because
 * {@code ExternalCallExecutor} cancels the future without interrupting the HTTP worker thread;
 * without an aligned client timeout a hung request would leak the worker thread.
 *
 * @since 0.1.0
 */
public class GovernedMem0Api {
    private static final Logger LOG = Logger.getLogger(GovernedMem0Api.class.getName());

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String OP = "memory";

    private static final String AUTH_MODE_TOKEN = "token";

    private static final String AUTH_MODE_X_API_KEY = "x_api_key";

    private static final String AUTH_MODE_BEARER = "bearer";

    private static final String PATH_STYLE_V3 = "v3";

    private static final String PATH_STYLE_OPEN = "open";

    private final ExternalCallExecutor executor;

    private final HttpClient httpClient;

    private final Duration requestTimeout;

    private final boolean shouldRetry;

    private final String authHeaderMode;

    private final String apiKey;

    private final String pathStyle;

    /**
     * Creates a governed mem0 transport.
     *
     * @param targetId audit/circuit target label (for example the endpoint)
     * @param policy external call policy (timeout/retry/circuit/audit)
     */
    public GovernedMem0Api(String targetId, ExternalCallPolicy policy) {
        this(targetId, policy, "token", "", "v3");
    }

    /**
     * Creates a governed mem0 transport with explicit auth header mode.
     *
     * @param targetId audit/circuit target label (for example the endpoint)
     * @param policy external call policy (timeout/retry/circuit/audit)
     * @param authHeaderMode auth header mode: {@code token} (Authorization: Token),
     *                       {@code x_api_key} (X-API-Key), or {@code bearer} (Authorization: Bearer)
     * @param apiKey API key used for request authentication
     */
    public GovernedMem0Api(String targetId, ExternalCallPolicy policy, String authHeaderMode, String apiKey) {
        this(targetId, policy, authHeaderMode, apiKey, "v3");
    }

    /**
     * Creates a governed mem0 transport with explicit auth and path style.
     *
     * @param targetId audit/circuit target label (for example the endpoint)
     * @param policy external call policy (timeout/retry/circuit/audit)
     * @param authHeaderMode auth header mode: {@code token} (Authorization: Token),
     *                       {@code x_api_key} (X-API-Key), or {@code bearer} (Authorization: Bearer)
     * @param apiKey API key used for request authentication
     * @param pathStyle path style: {@code v3} for mem0 cloud (v3/v1 paths),
     *                  {@code open} for self-hosted mem0 (simple paths)
     */
    public GovernedMem0Api(String targetId, ExternalCallPolicy policy, String authHeaderMode, String apiKey,
        String pathStyle) {
        this.executor = new ExternalCallExecutor("Memory", targetId, policy,
            ExternalSvcAdapterErrorCode.MEMORY_OUTBOUND_CALL_FAILED,
            ExternalSvcAdapterErrorCode.MEMORY_CIRCUIT_OPEN,
            ExternalSvcAdapterErrorCode.MEMORY_RETRY_INTERRUPTED,
            ExternalSvcAdapterErrorCode.MEMORY_TIMEOUT);
        int timeoutMs = policy != null ? policy.getTimeoutMs() : 30000;
        this.requestTimeout = Duration.ofMillis(Math.max(1, timeoutMs));
        this.shouldRetry = policy != null && policy.getRetry() != null && policy.getRetry().getMax() > 0;
        this.httpClient = HttpClient.newBuilder().connectTimeout(requestTimeout).build();
        this.authHeaderMode = normalizeAuthMode(authHeaderMode);
        this.apiKey = apiKey != null ? apiKey : "";
        this.pathStyle = normalizePathStyle(pathStyle);
    }

    GovernedMem0Api(ExternalCallExecutor executor,
        HttpClient httpClient, Duration requestTimeout, boolean shouldRetry) {
        this(executor, httpClient, requestTimeout, shouldRetry, "token", "", "v3");
    }

    GovernedMem0Api(ExternalCallExecutor executor, HttpClient httpClient, Duration requestTimeout,
        boolean shouldRetry, String authHeaderMode, String apiKey) {
        this(executor, httpClient, requestTimeout, shouldRetry, authHeaderMode, apiKey, "v3");
    }

    GovernedMem0Api(ExternalCallExecutor executor, HttpClient httpClient, Duration requestTimeout,
        boolean shouldRetry, String authHeaderMode, String apiKey, String pathStyle) {
        this.executor = executor;
        this.httpClient = httpClient;
        this.requestTimeout = requestTimeout;
        this.shouldRetry = shouldRetry;
        this.authHeaderMode = normalizeAuthMode(authHeaderMode);
        this.apiKey = apiKey != null ? apiKey : "";
        this.pathStyle = normalizePathStyle(pathStyle);
    }

    private static String normalizeAuthMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return AUTH_MODE_TOKEN;
        }
        String normalized = mode.trim().toLowerCase().replace("-", "_");
        if (AUTH_MODE_TOKEN.equals(normalized) || AUTH_MODE_X_API_KEY.equals(normalized)
            || AUTH_MODE_BEARER.equals(normalized)) {
            return normalized;
        }
        LOG.log(Level.WARNING, "Unrecognized auth-header-mode ''{0}'', falling back to ''{1}''",
            new Object[]{mode, AUTH_MODE_TOKEN});
        return AUTH_MODE_TOKEN;
    }

    private static String normalizePathStyle(String style) {
        if (style == null || style.isBlank()) {
            return PATH_STYLE_V3;
        }
        String normalized = style.trim().toLowerCase();
        if (PATH_STYLE_OPEN.equals(normalized)) {
            return PATH_STYLE_OPEN;
        }
        if (!PATH_STYLE_V3.equals(normalized)) {
            LOG.log(Level.WARNING, "Unrecognized path-style ''{0}'', falling back to ''{1}''",
                new Object[]{style, PATH_STYLE_V3});
        }
        return PATH_STYLE_V3;
    }

    private String searchPath() {
        return PATH_STYLE_OPEN.equals(pathStyle) ? "/search" : "/v3/memories/search/";
    }

    private String addPath() {
        return PATH_STYLE_OPEN.equals(pathStyle) ? "/memories" : "/v3/memories/add/";
    }

    private String getAllPath() {
        return PATH_STYLE_OPEN.equals(pathStyle) ? "/memories" : "/v3/memories/";
    }

    private String memoryPath(String memoryId) {
        String encoded = encodeSegment(memoryId);
        return PATH_STYLE_OPEN.equals(pathStyle)
            ? "/memories/" + encoded
            : "/v1/memories/" + encoded + "/";
    }

    /**
     * Search options for mem0 search requests.
     *
     * @since 0.1.0
     */
    public static final class SearchMemoriesOptions {
        private final Map<String, Object> filters;

        private final boolean shouldRerank;

        private final int topK;

        private SearchMemoriesOptions(Map<String, Object> filters, boolean shouldRerank, int topK) {
            this.filters = filters != null ? new LinkedHashMap<>(filters) : Map.of();
            this.shouldRerank = shouldRerank;
            this.topK = topK;
        }

        /**
         * Creates mem0 search options.
         *
         * @param filters mem0 filter fields
         * @param shouldRerank whether mem0 reranking is enabled
         * @param topK maximum number of records
         * @return mem0 search options
         */
        public static SearchMemoriesOptions of(Map<String, Object> filters, boolean shouldRerank, int topK) {
            return new SearchMemoriesOptions(filters, shouldRerank, topK);
        }
    }

    /**
     * Gets all memories that match the supplied mem0 filters.
     *
     * @param baseUrl mem0 base url
     * @param apiKey mem0 API key
     * @param filters mem0 filter fields
     * @return memory records returned by mem0
     */
    public List<Map<String, Object>> getAllMemories(String baseUrl, String apiKey, Map<String, Object> filters) {
        String method = PATH_STYLE_OPEN.equals(pathStyle) ? "GET" : "POST";
        Map<String, Object> body = PATH_STYLE_OPEN.equals(pathStyle) ? null : new LinkedHashMap<>();
        if (body != null) {
            body.put("filters", filters);
        } else {
            // open style uses GET with no body; filters are not supported
            if (filters != null && !filters.isEmpty()) {
                LOG.log(Level.WARNING,
                    "filters parameter is ignored when path-style=''open'' "
                    + "(Mem0 OSS does not support filtering on GET /memories)");
            }
        }
        Map<String, Object> response = executor.execute(OP, "getAll", shouldRetry,
            () -> send(baseUrl, getAllPath(), apiKey, method, body));
        return extractResults(response);
    }

    /**
     * Searches memories by semantic query and mem0 filters.
     *
     * @param baseUrl mem0 base url
     * @param apiKey mem0 API key
     * @param query semantic search query
     * @param options mem0 search options
     * @return memory records returned by mem0
     */
    public List<Map<String, Object>> searchMemories(String baseUrl, String apiKey, String query,
        SearchMemoriesOptions options) {
        SearchMemoriesOptions effectiveOptions = options != null
            ? options
            : SearchMemoriesOptions.of(Map.of(), false, 0);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        body.put("filters", effectiveOptions.filters);
        body.put("rerank", effectiveOptions.shouldRerank);
        body.put("top_k", effectiveOptions.topK);
        Map<String, Object> response = executor.execute(OP, "search", shouldRetry,
            () -> send(baseUrl, searchPath(), apiKey, "POST", body));
        return extractResults(response);
    }

    /**
     * Adds memories to mem0 without returning the created records.
     *
     * @param baseUrl mem0 base url
     * @param apiKey mem0 API key
     * @param messages messages to store
     * @param scope mem0 scope fields
     * @param shouldInfer whether mem0 should infer facts from the supplied messages
     */
    public void addMemories(String baseUrl, String apiKey, List<Map<String, Object>> messages,
        Map<String, Object> scope, boolean shouldInfer) {
        addMemoryRecords(baseUrl, apiKey, messages, scope, shouldInfer);
    }

    /**
     * Adds memories and returns records from the backing service when present.
     *
     * @param baseUrl mem0 base url
     * @param apiKey mem0 API key
     * @param messages messages to store
     * @param scope mem0 scope fields
     * @param shouldInfer whether mem0 should infer facts from the supplied messages
     * @return records returned by mem0
     */
    public List<Map<String, Object>> addMemoryRecords(String baseUrl, String apiKey, List<Map<String, Object>> messages,
        Map<String, Object> scope, boolean shouldInfer) {
        Map<String, Object> body = new LinkedHashMap<>(scope != null ? scope : Map.of());
        body.put("messages", messages);
        body.put("infer", shouldInfer);
        Map<String, Object> response = executor.execute(OP, "add", shouldRetry,
            () -> send(baseUrl, addPath(), apiKey, "POST", body));
        return extractResults(response);
    }

    /**
     * Gets one memory record by mem0 memory id.
     *
     * @param baseUrl mem0 base url
     * @param apiKey mem0 API key
     * @param memoryId mem0 memory id
     * @return memory record returned by mem0
     */
    public Map<String, Object> getMemory(String baseUrl, String apiKey, String memoryId) {
        return executor.execute(OP, "get", shouldRetry,
            () -> send(baseUrl, memoryPath(memoryId), apiKey, "GET", null));
    }

    /**
     * Deletes one memory record by mem0 memory id.
     *
     * @param baseUrl mem0 base url
     * @param apiKey mem0 API key
     * @param memoryId mem0 memory id
     */
    public void deleteMemory(String baseUrl, String apiKey, String memoryId) {
        executor.execute(OP, "delete", shouldRetry,
            () -> send(baseUrl, memoryPath(memoryId), apiKey, "DELETE", null));
    }

    private Map<String, Object> send(String baseUrl, String path, String apiKey, String method,
        Map<String, Object> body) throws Exception {
        String normalizedBase = (baseUrl == null || baseUrl.isBlank())
            ? "https://api.mem0.ai"
            : baseUrl.replaceAll("/+$", "");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(normalizedBase + path))
            .timeout(requestTimeout)
            .header("Accept", "application/json");
        String effectiveApiKey = (apiKey != null && !apiKey.isBlank()) ? apiKey : this.apiKey;
        if (AUTH_MODE_X_API_KEY.equals(authHeaderMode)) {
            builder.header("X-API-Key", effectiveApiKey);
        } else if (AUTH_MODE_BEARER.equals(authHeaderMode)) {
            builder.header("Authorization", "Bearer " + effectiveApiKey);
        } else {
            builder.header("Authorization", "Token " + effectiveApiKey);
        }
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
                "Mem0 request failed with status " + response.statusCode() + ": " + response.body());
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

    private static String encodeSegment(String value) {
        String safe = value == null ? "" : value;
        return URLEncoder.encode(safe, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Map<String, Object> castMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
