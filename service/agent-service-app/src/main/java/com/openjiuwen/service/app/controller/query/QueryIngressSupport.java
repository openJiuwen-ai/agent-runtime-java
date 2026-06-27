/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.query;

import com.openjiuwen.service.spec.dto.QueryRequest;
import com.openjiuwen.service.spec.dto.ServeRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpHeaders;

/**
 * Shared ingress logic for Query controllers (header binding, validation, DTO mapping).
 */
public final class QueryIngressSupport {

    public static final String HEADER_USER_ID = "X-User-ID";
    public static final String HEADER_SPACE_ID = "X-Space-ID";
    public static final String HEADER_TENANT_ID = "X-Tenant-ID";

    private static final Set<String> EXCLUDED_HEADERS = Set.of("authorization", "cookie", "set-cookie", "x-api-key",
            "proxy-authorization", "x-csrf-token");

    private QueryIngressSupport() {
    }

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

    public record ValidationResult(boolean valid, int errorStatus, Map<String, Object> errorBody,
            ServeRequest serveRequest) {

        static ValidationResult ok(ServeRequest serveRequest) {
            return new ValidationResult(true, 0, null, serveRequest);
        }

        static ValidationResult error(int status, Map<String, Object> body) {
            return new ValidationResult(false, status, body, null);
        }
    }

    /**
     * Build request metadata for telemetry/audit, collecting headers, query, path, and body.
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

    public static Map<String, Object> serviceUnavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", "no agent handler configured");
        return body;
    }

    public static Map<String, Object> agentNotReady() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", "agent not loaded");
        return body;
    }
}
