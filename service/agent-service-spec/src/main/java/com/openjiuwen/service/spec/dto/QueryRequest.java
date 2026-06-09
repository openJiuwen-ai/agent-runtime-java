package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External Query API request body (aligned with Python {@code QueryRequest}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueryRequest {

    private List<Map<String, Object>> messages = new ArrayList<>();

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("user_id")
    private String userId = "anonymous";

    @JsonProperty("space_id")
    private String spaceId = "default";

    @JsonProperty("tenant_id")
    private String tenantId;

    private boolean stream = true;

    /** Issue-compatible single-turn shorthand; normalized to {@link #messages} in {@link #normalizeMessages()}. */
    @JsonProperty("message")
    private String message;

    /**
     * If {@link #message} is set and {@link #messages} is empty, wrap it as a single user message.
     */
    public void normalizeMessages() {
        if ((messages == null || messages.isEmpty()) && message != null && !message.isBlank()) {
            Map<String, Object> userMsg = new LinkedHashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", message);
            messages = new ArrayList<>();
            messages.add(userMsg);
        }
        if (messages == null) {
            messages = new ArrayList<>();
        }
    }

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
        this.userId = userId != null ? userId : "anonymous";
    }

    public String getSpaceId() {
        return spaceId;
    }

    public void setSpaceId(String spaceId) {
        this.spaceId = spaceId != null ? spaceId : "default";
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
