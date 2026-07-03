/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

/**
 * Non-streaming Query API response.
 * <p>
 * {@link #result} carries the aggregated assistant output (role, content,
 * events).
 *
 * @since 0.1.0
 */
@Data
public class QueryResponse {

    @JsonProperty("result")
    private Object result;

    @JsonProperty("conversation_id")
    private String conversationId;

    /** Default constructor for JSON deserialization. */
    public QueryResponse() {
    }

    /**
     * Creates a query response with result and conversation identifier.
     *
     * @param result the aggregated result payload
     * @param conversationId the conversation identifier
     */
    public QueryResponse(Object result, String conversationId) {
        this.result = result;
        this.conversationId = conversationId;
    }
}
