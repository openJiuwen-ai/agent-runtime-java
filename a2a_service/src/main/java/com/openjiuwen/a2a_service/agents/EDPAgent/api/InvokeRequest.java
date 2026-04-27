package com.openjiuwen.a2a_service.agents.EDPAgent.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

public class InvokeRequest {

    private String query = "";
    @JsonProperty("conversation_id")
    @JsonAlias("conversationId")
    private String conversationId = "";
    private boolean stream = true;
    @JsonProperty("custom_data")
    @JsonAlias("customData")
    private Map<String, Object> customData = new LinkedHashMap<String, Object>();

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public boolean isStream() {
        return stream;
    }

    public void setStream(boolean stream) {
        this.stream = stream;
    }

    public Map<String, Object> getCustomData() {
        return customData;
    }

    public void setCustomData(Map<String, Object> customData) {
        this.customData = customData != null ? new LinkedHashMap<String, Object>(customData) : new LinkedHashMap<String, Object>();
    }
}
