package com.openjiuwen.service.spec.dto;

/**
 * Non-streaming Query API response.
 */
public class QueryResponse {

    private Object result;
    private String conversationId;

    public QueryResponse() {
    }

    public QueryResponse(Object result, String conversationId) {
        this.result = result;
        this.conversationId = conversationId;
    }

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }
}
