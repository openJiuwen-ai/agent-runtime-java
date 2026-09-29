/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import lombok.Data;

/**
 * Reset conversation API request body (aligned with Python
 * {@code ResetConversationRequest}).
 *
 * @since 0.1.0
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResetConversationRequest {
    @JsonProperty("agent_id")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String agentId;

    @JsonProperty("conversation_id")
    private String conversationId;

    @JsonProperty("user_id")
    private String userId;

    /**
     * Accepts only a JSON string or null without changing global coercion rules.
     *
     * @param agentId optional hosted routing identifier
     */
    @JsonSetter("agent_id")
    public void setAgentId(Object agentId) {
        if (agentId != null && !(agentId instanceof String)) {
            throw new IllegalArgumentException("agent_id must be a string or null");
        }
        this.agentId = (String) agentId;
    }
}
