package com.openjiuwen.a2a_service.agents.EDPAgent.adapter;

import java.util.LinkedHashMap;
import java.util.Map;

public final class RequestAdapter {

    private RequestAdapter() {
    }

    public static Map<String, Object> injectQuery(Map<String, Object> originalBody, String taskDescription) {
        Map<String, Object> modified = deepCopy(originalBody);
        Object input = modified.get("input");
        if (input instanceof Map<?, ?> map) {
            Map<String, Object> inputMap = castMap(map);
            inputMap.put("query", taskDescription);
            modified.put("input", inputMap);
            return modified;
        }
        modified.put("query", taskDescription);
        return modified;
    }

    public static String extractUserQuery(Map<String, Object> body) {
        if (body == null) {
            return "";
        }
        Object input = body.get("input");
        if (input instanceof Map<?, ?> map) {
            Object query = map.get("query");
            return query != null ? String.valueOf(query) : "";
        }
        Object query = body.get("query");
        if (query != null) {
            return String.valueOf(query);
        }
        Object message = body.get("message");
        return message != null ? String.valueOf(message) : "";
    }

    private static Map<String, Object> deepCopy(Map<String, Object> originalBody) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (originalBody == null) {
            return copy;
        }
        for (Map.Entry<String, Object> entry : originalBody.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> map) {
                copy.put(entry.getKey(), castMap(map));
            } else {
                copy.put(entry.getKey(), value);
            }
        }
        return copy;
    }

    private static Map<String, Object> castMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
