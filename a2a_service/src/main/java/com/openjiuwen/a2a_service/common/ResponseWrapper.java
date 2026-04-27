package com.openjiuwen.a2a_service.common;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public final class ResponseWrapper {

    private static final Set<String> EVENTS_WITH_ERROR_CODE = Set.of("planning_execution_process");

    private ResponseWrapper() {
    }

    public static Map<String, Object> wrapAgentEvent(
            String eventType,
            String content,
            Map<String, Object> data,
            String agentId,
            String conversationId,
            double elapsedSeconds,
            String plugin
    ) {
        Map<String, Object> custom = new LinkedHashMap<String, Object>();
        custom.put("data", data != null ? data : Map.of());
        custom.put("event", eventType);
        custom.put("content", content != null ? content : "");
        custom.put("createdTime", System.currentTimeMillis());
        custom.put("latency", "");
        custom.put("plugin", plugin != null ? plugin : "");

        Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
        wrapped.put("success", Boolean.TRUE);
        wrapped.put("agent_id", agentId);
        wrapped.put("conversation_id", conversationId);
        wrapped.put("output", "");
        wrapped.put("error", "");
        wrapped.put("execution_time", elapsedSeconds);
        wrapped.put("custom_rsp_data", custom);
        if (EVENTS_WITH_ERROR_CODE.contains(eventType)) {
            wrapped.put("error_code", "");
        }
        return wrapped;
    }

    public static Map<String, Object> wrapWorkflowEvent(
            String eventKind,
            Map<String, Object> data,
            String agentId,
            String conversationId,
            double elapsedSeconds
    ) {
        Map<String, Object> custom = new LinkedHashMap<String, Object>();
        custom.put("event", eventKind);
        custom.put("data", data != null ? data : Map.of());

        Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
        wrapped.put("success", Boolean.TRUE);
        wrapped.put("agent_id", agentId);
        wrapped.put("conversation_id", conversationId);
        wrapped.put("execution_time", elapsedSeconds);
        wrapped.put("custom_rsp_data", custom);
        return wrapped;
    }

    public static Map<String, Object> wrapError(
            String agentId,
            String conversationId,
            double elapsedSeconds,
            String errorCode,
            String errorMsg
    ) {
        Map<String, Object> wrapped = new LinkedHashMap<String, Object>();
        wrapped.put("success", Boolean.FALSE);
        wrapped.put("agent_id", agentId);
        wrapped.put("conversation_id", conversationId);
        wrapped.put("execution_time", elapsedSeconds);
        wrapped.put("error_code", errorCode);
        wrapped.put("error_msg", errorMsg);
        return wrapped;
    }
}
