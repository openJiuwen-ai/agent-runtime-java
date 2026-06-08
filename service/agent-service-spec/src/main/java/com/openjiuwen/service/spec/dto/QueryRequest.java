package com.openjiuwen.service.spec.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * External Query API request body (aligned with Python {@code QueryRequest}).
 */
public class QueryRequest {

    private String conversationId;
    private List<Map<String, Object>> messages = new ArrayList<>();
    private String userId = "anonymous";
    private String spaceId = "default";
    private String tenantId;
    private boolean stream = true;

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public List<Map<String, Object>> getMessages() {
        return messages;
    }

    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(String spaceId) {
        this.spaceId = spaceId;
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }
}
