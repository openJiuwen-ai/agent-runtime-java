/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External Query API request body (aligned with Python {@code QueryRequest}).
 */
@Data
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

    /**
     * Issue-compatible single-turn shorthand; normalized to {@link #messages} in
     * {@link #normalizeMessages()}.
     */
    @JsonProperty("message")
    private String message;

    /**
     * If {@link #message} is set and {@link #messages} is empty, wrap it as a
     * single user message.
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

    public void setMessages(List<Map<String, Object>> messages) {
        this.messages = messages != null ? messages : new ArrayList<>();
    }

    public void setUserId(String userId) {
        this.userId = userId != null ? userId : "anonymous";
    }

    public void setSpaceId(String spaceId) {
        this.spaceId = spaceId != null ? spaceId : "default";
    }
}
