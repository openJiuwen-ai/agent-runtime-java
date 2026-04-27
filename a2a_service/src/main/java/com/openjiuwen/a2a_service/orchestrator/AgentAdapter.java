package com.openjiuwen.a2a_service.orchestrator;

import com.openjiuwen.a2a_service.common.Events;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 事件 → A2A 事件转换。
 *
 * 转换规则：
 *   ThoughtEvent    → 中间输出（text part）
 *   AnswerEvent     → 最终回答（完成时附带 completed 状态）
 *   DelegateRequest → null（由 Executor 直接处理）
 */
public class AgentAdapter {

    /**
     * 将 DPA Agent 事件转换为 A2A 事件描述（用于 SSE 推送）。
     *
     * @return 事件描述 Map，或 null 如果不需要推送
     */
    public static Map<String, Object> agentEventToA2a(Object event, String taskId, String convId) {
        if (event instanceof Events.ThoughtEvent thought) {
            return agentEvent("thought", thought.getContent(), Map.of(), "");
        }

        if (event instanceof Events.AnswerEvent answer) {
            return agentEvent(answer.isFinal() ? "final_answer_end" : "summary", answer.getContent(), Map.of(), "");
        }

        if (event instanceof Map<?, ?> map) {
            return mapEventToA2a(map, taskId, convId);
        }

        return null;
    }

    private static Map<String, Object> mapEventToA2a(Map<?, ?> event, String taskId, String convId) {
        Object rawType = event.get("type");
        Object rawContent = event.get("content");
        String type = rawType != null ? String.valueOf(rawType) : "";
        String content = rawContent != null ? String.valueOf(rawContent) : "";
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        Object nested = event.get("data");
        if (nested instanceof Map<?, ?> rawData) {
            for (Map.Entry<?, ?> entry : rawData.entrySet()) {
                if (entry.getKey() != null) {
                    payload.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        } else {
            for (Map.Entry<?, ?> entry : event.entrySet()) {
                String key = String.valueOf(entry.getKey());
                if (!"type".equals(key) && !"content".equals(key) && !"plugin".equals(key)) {
                    payload.put(key, entry.getValue());
                }
            }
        }
        String plugin = event.get("plugin") != null ? String.valueOf(event.get("plugin")) : "";
        return agentEvent(type, content, payload, plugin);
    }

    public static Map<String, Object> agentEvent(String type, String content, Map<String, Object> data, String plugin) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("_event_kind", "agent");
        result.put("event", type);
        result.put("content", content != null ? content : "");
        result.put("data", data != null ? data : Map.of());
        result.put("plugin", plugin != null ? plugin : "");
        return result;
    }

    public static Map<String, Object> workflowEvent(String event, Map<String, Object> data) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("_event_kind", "workflow");
        result.put("event", event);
        result.put("data", data != null ? data : Map.of());
        return result;
    }
}
