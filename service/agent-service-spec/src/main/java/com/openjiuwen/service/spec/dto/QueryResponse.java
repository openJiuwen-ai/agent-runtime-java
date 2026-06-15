/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Non-streaming Query API response.
 * <p>{@link #result} carries the aggregated assistant output (role, content, events).
 */
@Data
public class QueryResponse {

    @JsonProperty("result")
    private Object result;

    @JsonProperty("conversation_id")
    private String conversationId;

    public QueryResponse() {
    }

    public QueryResponse(Object result, String conversationId) {
        this.result = result;
        this.conversationId = conversationId;
    }
}
