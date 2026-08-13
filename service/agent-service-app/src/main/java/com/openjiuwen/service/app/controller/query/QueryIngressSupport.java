/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.query;

import com.openjiuwen.service.spec.dto.QueryRequest;
import com.openjiuwen.service.spec.dto.ServeRequest;

import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Shared ingress logic for Query controllers (header binding, validation, DTO mapping).
 *
 * @since 0.1.0
 */
public final class QueryIngressSupport {
    /** Gateway user identifier header. */
    public static final String HEADER_USER_ID = "X-User-ID";

    /** Gateway space identifier header. */
    public static final String HEADER_SPACE_ID = "X-Space-ID";

    /** Gateway tenant identifier header. */
    public static final String HEADER_TENANT_ID = "X-Tenant-ID";

    static final String LEGACY_PATH_PROPERTY = "openjiuwen.service.query.legacy-path-enabled";

    static final String WEBFLUX_ENABLED_PROPERTY = "openjiuwen.service.query.webflux.enabled";

    static final String CONVERSATION_ID_ATTRIBUTE = QueryIngressSupport.class.getName() + ".conversationId";

    private static final Set<String> EXCLUDED_HEADERS = Set.of("authorization", "cookie", "set-cookie", "x-api-key",
            "proxy-authorization", "x-csrf-token");

    private QueryIngressSupport() {
    }

    /**
     * Validates the query request and builds a {@link ServeRequest}.
     *
     * @param request the query request body
     * @param headers the HTTP headers
     * @return the validation result
     */
    public static ValidationResult validateAndBuild(QueryRequest request, HttpHeaders headers) {
        request.normalizeMessages();
        if (request.getConversationId() == null || request.getConversationId().isBlank()) {
            return ValidationResult.error(400, Map.of("type", "error", "error", "conversation_id is required"));
        }
        applyTenantHeaders(request, headers);
        return ValidationResult.ok(ServeRequest.fromQueryRequest(request));
    }

    private static void applyTenantHeaders(QueryRequest request, HttpHeaders headers) {
        if (headers == null) {
            return;
        }
        String headerUser = headers.getFirst(HEADER_USER_ID);
        if (headerUser != null && !headerUser.isBlank()) {
            request.setUserId(headerUser);
        }
        String headerSpace = headers.getFirst(HEADER_SPACE_ID);
        if (headerSpace != null && !headerSpace.isBlank()) {
            request.setSpaceId(headerSpace);
        }
        String headerTenant = headers.getFirst(HEADER_TENANT_ID);
        if (headerTenant != null && !headerTenant.isBlank()) {
            request.setTenantId(headerTenant);
        }
    }

    /**
     * Validation result containing the resolved {@link ServeRequest} or error details.
     */
    public record ValidationResult(boolean isValid, int errorStatus, Map<String, Object> errorBody,
            ServeRequest serveRequest) {
        static ValidationResult ok(ServeRequest serveRequest) {
            return new ValidationResult(true, 0, null, serveRequest);
        }

        static ValidationResult error(int status, Map<String, Object> body) {
            return new ValidationResult(false, status, body, null);
        }
    }

    /**
     * Builds request metadata for telemetry/audit, collecting headers, query, path, and body.
     *
     * @param headers the HTTP request headers
     * @param queryParams the query parameters from the request
     * @param path the request URI path
     * @param bodyMap the parsed request body as a map
     * @return a new metadata map with filtered headers, query params, path, and body
     */
    public static Map<String, Object> buildMetadata(HttpHeaders headers, Map<String, String> queryParams, String path,
            Map<String, Object> bodyMap) {
        Map<String, Object> metadata = new LinkedHashMap<>();

        Map<String, String> headerMap = new LinkedHashMap<>();
        headers.forEach((k, v) -> {
            if (!EXCLUDED_HEADERS.contains(k.toLowerCase())) {
                headerMap.put(k.toLowerCase(), v.get(0));
            }
        });
        metadata.put("headers", headerMap);

        metadata.put("query", queryParams != null ? queryParams : Map.of());
        metadata.put("path", path != null ? path : "");
        metadata.put("body", bodyMap != null ? bodyMap : Map.of());

        return metadata;
    }

    /**
     * Returns a standard "service unavailable" error body.
     *
     * @return the error body map
     */
    public static Map<String, Object> serviceUnavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", "no agent handler configured");
        return body;
    }

    /**
     * Returns a standard "agent not ready" error body.
     *
     * @return the error body map
     */
    public static Map<String, Object> agentNotReady() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", "agent not loaded");
        return body;
    }

    /**
     * Returns a standard error body when the SSE pump thread pool is saturated.
     *
     * @return the error body map
     */
    public static Map<String, Object> streamPumpSaturated() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("code", "STREAM_PUMP_SATURATED");
        body.put("error", "stream pump pool saturated");
        return body;
    }

    /**
     * Returns the stable error contract for a synchronous agent execution failure.
     *
     * @param conversationId the conversation identifier
     * @return the error body map
     */
    public static Map<String, Object> agentExecutionFailed(String conversationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("code", "AGENT_EXECUTION_FAILED");
        body.put("error", "agent execution failed");
        body.put("conversation_id", conversationId);
        return body;
    }
}
