/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.versatile;

import com.openjiuwen.service.adapters.versatile.autoconfigure.VersatileProperties;
import com.openjiuwen.service.adapters.versatile.external.VersatileHttpClient;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link AgentHandler} that delegates to a remote Versatile RESTful workflow service so low-code
 * flows can be integrated as tools/skills by high-code Agents.
 */
public class VersatileAgentHandler implements AgentHandler {

    private static final Logger log = LoggerFactory.getLogger(VersatileAgentHandler.class);

    private final VersatileHttpClient httpClient;
    private final VersatileProperties properties;

    public VersatileAgentHandler(VersatileHttpClient httpClient, VersatileProperties properties) {
        this.httpClient = httpClient;
        this.properties = properties;
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        try {
            Map<String, Object> remote = httpClient.postQuery(buildRemoteBody(request));
            return toQueryResponse(remote, request.getConversationId());
        } catch (Exception ex) {
            log.error("Versatile unary query failed for conversation_id={}", request.getConversationId(), ex);
            throw new VersatileInvocationException("Versatile query failed", ex);
        }
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        try {
            Map<String, Object> remote = request.isStream()
                    ? httpClient.postStreamQuery(buildRemoteBody(request))
                    : httpClient.postQuery(buildRemoteBody(request));
            observer.onNext(new QueryChunk("chunk", normalizeRemotePayload(remote)));
            observer.onComplete();
        } catch (Exception ex) {
            log.error("Versatile stream query failed for conversation_id={}", request.getConversationId(), ex);
            observer.onNext(new QueryChunk("error", errorEvent(ex)));
            observer.onError(ex);
        }
    }

    private Map<String, Object> buildRemoteBody(ServeRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("conversation_id", request.getConversationId());
        body.put("messages", request.getMessages());
        body.put("user_id", request.getUserId());
        body.put("space_id", request.getSpaceId());
        if (request.getTenantId() != null) {
            body.put("tenant_id", request.getTenantId());
        }
        body.put("stream", request.isStream());
        String query = request.lastUserQuery();
        if (query != null && !query.isBlank()) {
            body.put("message", query);
        }
        if (properties.getWorkflowId() != null && !properties.getWorkflowId().isBlank()) {
            body.put("workflow_id", properties.getWorkflowId());
        }
        return body;
    }

    private static QueryResponse toQueryResponse(Map<String, Object> remote, String conversationId) {
        Object result = remote.get("result");
        if (result instanceof Map<?, ?>) {
            return new QueryResponse((Map<String, Object>) result, conversationId);
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("role", "assistant");
        wrapped.put("content", remote.getOrDefault("content", remote));
        return new QueryResponse(wrapped, conversationId);
    }

    private static Map<String, Object> normalizeRemotePayload(Map<String, Object> remote) {
        if (remote.containsKey("result") && remote.get("result") instanceof Map<?, ?>) {
            return (Map<String, Object>) remote.get("result");
        }
        return remote;
    }

    private static Map<String, Object> errorEvent(Exception ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "error");
        error.put("error", ex.getMessage() != null ? ex.getMessage() : ex.toString());
        return error;
    }

    public static class VersatileInvocationException extends RuntimeException {
        public VersatileInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
