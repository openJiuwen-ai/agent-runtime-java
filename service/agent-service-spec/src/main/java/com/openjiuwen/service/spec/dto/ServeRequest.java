/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * Protocol-neutral orchestration request (Ingress DTO → internal model).
 *
 * @since 0.1.0
 */
@Data
public class ServeRequest {
    private String conversationId;
    private List<Map<String, Object>> messages = new ArrayList<>();
    private String userId;
    private String spaceId;
    private String tenantId;
    private boolean stream = true;
    private Map<String, Object> metadata = new LinkedHashMap<>();

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
        return serveRequest;
    }

    /**
     * Extract the latest user message content as the agent query.
     *
     * @return the latest user message content, or empty string if none found
     */
    public String lastUserQuery() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Map<String, Object> m = messages.get(i);
            if (m == null) {
                continue;
            }
            Object role = m.get("role");
            Object content = m.get("content");
            if (role != null && "user".equalsIgnoreCase(String.valueOf(role)) && content != null) {
                return String.valueOf(content);
            }
        }
        if (!messages.isEmpty()) {
            Map<String, Object> last = messages.get(messages.size() - 1);
            if (last != null && last.get("content") != null) {
                return String.valueOf(last.get("content"));
            }
        }
        return "";
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
