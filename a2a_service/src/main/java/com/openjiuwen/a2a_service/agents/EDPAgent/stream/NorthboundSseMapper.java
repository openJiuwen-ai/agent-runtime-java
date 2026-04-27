package com.openjiuwen.a2a_service.agents.EDPAgent.stream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

public class NorthboundSseMapper {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public String buildSseLine(SseEvent event) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("event", event.getEvent());
        payload.put("content", event.getContent());
        payload.put("data", event.getData());
        payload.put("plugin", event.getPlugin());
        try {
            return "data: " + MAPPER.writeValueAsString(payload) + "\n\n";
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build SSE line", e);
        }
    }

    public String buildNorthboundSseLine(SseEvent event, String agentId, String conversationId, boolean success, String error,
                                         double executionTime) {
        Map<String, Object> rspData = new LinkedHashMap<String, Object>();
        rspData.put("data", event.getData());
        rspData.put("event", event.getEvent());
        rspData.put("content", event.getContent());
        rspData.put("createdTime", System.currentTimeMillis());
        rspData.put("latency", "");
        rspData.put("plugin", event.getPlugin());

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("success", success);
        payload.put("agent_id", agentId);
        payload.put("conversation_id", conversationId);
        payload.put("output", "");
        payload.put("error", error != null ? error : "");
        payload.put("execution_time", executionTime);
        payload.put("custom_rsp_data", rspData);
        try {
            return "data: " + MAPPER.writeValueAsString(payload) + "\n\n";
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to build northbound SSE line", e);
        }
    }
}
