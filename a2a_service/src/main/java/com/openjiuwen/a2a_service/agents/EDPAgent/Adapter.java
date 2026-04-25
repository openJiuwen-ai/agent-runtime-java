package com.openjiuwen.a2a_service.agents.EDPAgent;

import java.util.Map;

/**
 * DPA Agent 适配工具。
 *
 * injectQuery: 将 taskDescription 注入到 Versatile 请求体的 query 字段，
 *              供 WorkflowAgent 使用。
 *
 * extractUserQuery: 从 Versatile 格式 body 中提取用户输入文本
 *                   （与 agent_runtime 侧的同名函数功能相同，DPA 侧独立维护以避免耦合）。
 */
public final class Adapter {

    private Adapter() {}

    /**
     * 将 taskDescription 注入到 Versatile 请求体的 query 字段。
     *
     * 注入策略：
     *   1. 如果 body 有 input.query，替换为 taskDescription
     *   2. 否则在顶层设置 query 字段（兼容简单格式）
     *
     * 返回深拷贝，不修改原始 body。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> injectQuery(Map<String, Object> originalBody, String taskDescription) {
        Map<String, Object> modified = deepCopy(originalBody);

        Object inputObj = modified.get("input");
        if (inputObj instanceof Map) {
            ((Map<String, Object>) inputObj).put("query", taskDescription);
        } else {
            modified.put("query", taskDescription);
        }

        return modified;
    }

    /**
     * 从 Versatile 格式 body 中提取用户输入文本。
     *
     * 支持格式：
     *   - {"input": {"query": "..."}}
     *   - {"query": "..."}
     *   - {"message": "..."}
     */
    @SuppressWarnings("unchecked")
    public static String extractUserQuery(Map<String, Object> body) {
        if (body == null) {
            return "";
        }
        Object inputObj = body.get("input");
        if (inputObj instanceof Map) {
            Object query = ((Map<String, Object>) inputObj).get("query");
            if (query instanceof String && !((String) query).isEmpty()) {
                return (String) query;
            }
        }
        Object query = body.get("query");
        if (query instanceof String && !((String) query).isEmpty()) {
            return (String) query;
        }
        Object message = body.get("message");
        if (message instanceof String && !((String) message).isEmpty()) {
            return (String) message;
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deepCopy(Map<String, Object> original) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : original.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                copy.put(entry.getKey(), deepCopy((Map<String, Object>) value));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }
}
