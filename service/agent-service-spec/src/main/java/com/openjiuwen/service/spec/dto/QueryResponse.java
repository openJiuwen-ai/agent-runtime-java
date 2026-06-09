package com.openjiuwen.service.spec.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Non-streaming Query API response.
 * <p>{@link #result} carries the aggregated assistant output (role, content, events).
 */
public class QueryResponse {

    private Object result;

    @JsonProperty("conversation_id")
    private String conversationId;

    public QueryResponse() {
    }

    public QueryResponse(Object result, String conversationId) {
        this.result = result;
        this.conversationId = conversationId;
    }

    @JsonProperty("result")
    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    @JsonProperty("conversation_id")
    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
}
