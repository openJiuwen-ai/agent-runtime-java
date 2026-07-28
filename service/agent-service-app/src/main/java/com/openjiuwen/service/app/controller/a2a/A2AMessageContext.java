/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import lombok.Data;

import com.openjiuwen.service.app.controller.query.QueryIngressSupport;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal DTO wrapping A2A SDK parsed message context.
 *
 * @since 0.1.0
 */
@Data
public class A2AMessageContext {
    private static final Logger log = LoggerFactory.getLogger(A2AMessageContext.class);

    /** State key for ingress HTTP tenant headers propagated from {@link A2aJsonRpcController}. */
    public static final String INGRESS_HEADERS_STATE_KEY = "_ingress_http_headers";

    private Message a2aMessage;

    private String contextId;

    private String taskId;

    private Map<String, Object> metadata;

    private Map<String, String> headers;

    /**
     * Creates an {@link A2AMessageContext} from the SDK request context.
     *
     * @param ctx the SDK request context
     * @return the populated message context
     */
    public static A2AMessageContext from(RequestContext ctx) {
        A2AMessageContext c = new A2AMessageContext();
        c.a2aMessage = ctx.getMessage();
        c.contextId = ctx.getContextId();
        c.taskId = ctx.getTaskId();
        c.metadata = ctx.getMetadata();

        Task existingTask = ctx.getTask();
        if (existingTask != null) {
            int historySize = existingTask.history() != null ? existingTask.history().size() : 0;
            log.info("A2A RESUME taskId={} contextId={} existingTaskId={} existingContextId={} historySize={}",
                c.taskId, c.contextId, existingTask.id(), existingTask.contextId(), historySize);
        } else {
            log.info("A2A NEW task taskId={} contextId={}", c.taskId, c.contextId);
        }

        c.headers = ingressHeaders(ctx);
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> ingressHeaders(RequestContext ctx) {
        ServerCallContext callContext = ctx.getCallContext();
        if (callContext == null) {
            return null;
        }
        Object raw = callContext.getState().get(INGRESS_HEADERS_STATE_KEY);
        if (!(raw instanceof Map<?, ?> rawHeaders)) {
            return null;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        rawHeaders.forEach((key, value) -> {
            if (key instanceof String headerName && value instanceof String headerValue) {
                headers.put(headerName, headerValue);
            }
        });
        return headers.isEmpty() ? null : headers;
    }

    /**
     * Builds lowercase ingress tenant headers from an HTTP servlet request.
     *
     * @param request HTTP servlet request
     * @return header map for {@link ServerCallContext} state, possibly empty
     */
    public static Map<String, String> tenantHeadersFrom(jakarta.servlet.http.HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        putTenantHeader(headers, "x-user-id", request.getHeader(QueryIngressSupport.HEADER_USER_ID));
        putTenantHeader(headers, "x-space-id", request.getHeader(QueryIngressSupport.HEADER_SPACE_ID));
        putTenantHeader(headers, "x-tenant-id", request.getHeader(QueryIngressSupport.HEADER_TENANT_ID));
        return headers;
    }

    private static void putTenantHeader(Map<String, String> headers, String key, String value) {
        if (value != null && !value.isBlank()) {
            headers.put(key, value);
        }
    }
}
