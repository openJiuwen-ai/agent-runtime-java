package com.openjiuwen.service.app.controller.query;

import com.openjiuwen.service.spec.dto.QueryRequest;
import com.openjiuwen.service.spec.dto.ServeRequest;
import org.springframework.http.HttpHeaders;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared ingress logic for Query controllers (header binding, validation, DTO mapping).
 */
public final class QueryIngressSupport {

    public static final String HEADER_USER_ID = "X-User-ID";
    public static final String HEADER_SPACE_ID = "X-Space-ID";
    public static final String HEADER_TENANT_ID = "X-Tenant-ID";

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

    public static Map<String, Object> serviceUnavailable() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "error");
        body.put("error", "no agent handler configured");
        return body;
    }
}
