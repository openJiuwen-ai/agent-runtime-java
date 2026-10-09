/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Protocol-neutral orchestration request (Ingress DTO → internal model).
 *
 * @since 0.1.0
 */
@Data
public class ServeRequest {
    private String conversationId;

    /** Public model alias for this request; null selects the deployment default. */
    private String modelName;

    /** Sets and normalizes a request model alias. */
    public void setModelName(String modelName) {
        this.modelName = normalizeModelName(modelName);
    }

    /**
     * Validates the model alias without JSON scalar coercion.
     * @param value raw protocol value
     * @return trimmed alias, or null for the default
     */
    public static String normalizeModelName(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.trim().isEmpty()) {
            throw new IllegalArgumentException("model_name must be a non-blank string or null");
        }
        return text.trim();
    }

    private List<Map<String, Object>> messages = new ArrayList<>();

    private String userId;

    private String spaceId;

    private String tenantId;

    private boolean stream = true;

    private Map<String, Object> metadata = new LinkedHashMap<>();

    /** Internal successful remote-response metadata keyed by toolCallId, never an ingress parameter. */
    @JsonIgnore
    private Map<String, Object> remoteResponseMetadata = new LinkedHashMap<>();

    /**
     * Builds a serve request from the external query request body.
     *
     * @param request the query request
     * @return the normalized serve request
     */
    public static ServeRequest fromQueryRequest(QueryRequest request) {
        request.normalizeMessages();
        ServeRequest serveRequest = new ServeRequest();
        serveRequest.setConversationId(request.getConversationId());
        serveRequest.setMessages(request.getMessages());
        serveRequest.setUserId(request.getUserId());
        serveRequest.setSpaceId(request.getSpaceId());
        serveRequest.setTenantId(request.getTenantId());
        serveRequest.setStream(request.isStream());
        serveRequest.setModelName(request.getModelName());
        return serveRequest;
    }

    /**
     * Extract the latest user message content as the agent query.
     *
     * @return the latest user message content, or empty string if none found
     */
    public String lastUserQuery() {
        return lastMessageWithContent().map(message -> String.valueOf(message.get("content"))).orElse("");
    }

    /**
     * Returns the normalized parts of the latest message selected by
     * {@link #lastUserQuery()}.
     *
     * @return latest user-message parts, or an empty list when absent
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> lastUserParts() {
        return lastMessageWithContent()
                .filter(message -> message.get("parts") instanceof List<?>)
                .map(message -> (List<Map<String, Object>>) message.get("parts"))
                .orElseGet(List::of);
    }

    /**
     * Returns a defensive copy of the metadata attached to the latest message selected by
     * {@link #lastUserQuery()}.
     *
     * @return latest user-message metadata, or an empty map when absent
     */
    public Map<String, Object> lastUserMessageMetadata() {
        Optional<Map<String, Object>> message = lastMessageWithContent();
        if (message.isEmpty() || !(message.get().get("metadata") instanceof Map<?, ?> rawMetadata)) {
            return Map.of();
        }
        Map<String, Object> messageMetadata = new LinkedHashMap<>();
        rawMetadata.forEach((key, value) -> {
            if (key instanceof String stringKey) {
                messageMetadata.put(stringKey, value);
            }
        });
        return messageMetadata;
    }

    private Optional<Map<String, Object>> lastMessageWithContent() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if (m == null) {
                continue;
            }
            Object role = m.get("role");
            Object content = m.get("content");
            if (role != null && "user".equalsIgnoreCase(String.valueOf(role)) && content != null) {
                return Optional.of(m);
            }
        }
        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            if (last != null && last.get("content") != null) {
                return Optional.of(last);
            }
        }
        return Optional.empty();
    }

    /**
     * Replaces the message list, using an empty list when {@code null}.
     *
     * @param messages the conversation messages
     */
    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }
}
