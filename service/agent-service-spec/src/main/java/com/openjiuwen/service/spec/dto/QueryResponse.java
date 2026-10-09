/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

import java.util.Map;

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

    /** Optional structured metadata produced by the successful request. */
    @JsonProperty("metadata")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> metadata;

    /**
     * Default constructor for JSON deserialization.
     */
    public QueryResponse() {
    }

    /**
     * Creates a query response with result and conversation identifier.
     *
     * @param result the aggregated result payload
     * @param conversationId the conversation identifier
     */
    public QueryResponse(Object result, String conversationId) {
        this(result, conversationId, null);
    }

    /**
     * Creates a response with optional structured response metadata.
     *
     * @param result the aggregated result payload
     * @param conversationId the conversation identifier
     * @param metadata request-scoped response metadata
     */
    public QueryResponse(Object result, String conversationId, Map<String, Object> metadata) {
        this.result = result;
        this.conversationId = conversationId;
        this.metadata = metadata;
    }
}
