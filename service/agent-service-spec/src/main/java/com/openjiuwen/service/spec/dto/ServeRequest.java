/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Protocol-neutral orchestration request (Ingress DTO → internal model).
 */
@Data
public class ServeRequest {

    private String conversationId;
    private List<Map<String, Object>> messages = new ArrayList<>();
    private String userId;
    private String spaceId;
    private String tenantId;
    private boolean stream = true;

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

    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }
}
