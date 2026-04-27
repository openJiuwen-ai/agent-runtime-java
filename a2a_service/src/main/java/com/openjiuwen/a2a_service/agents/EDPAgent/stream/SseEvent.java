package com.openjiuwen.a2a_service.agents.EDPAgent.stream;

import java.util.LinkedHashMap;
import java.util.Map;

public class SseEvent {

    private final String event;
    private final String content;
    private final Map<String, Object> data;
    private final String plugin;

    public SseEvent(String event, String content, Map<String, Object> data, String plugin) {
        this.event = event;
        this.content = content != null ? content : "";
        this.data = data != null ? new LinkedHashMap<String, Object>(data) : new LinkedHashMap<String, Object>();
        this.plugin = plugin != null ? plugin : "";
    }

    public String getEvent() {
        return event;
    }

    public String getContent() {
        return content;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public String getPlugin() {
        return plugin;
    }

    public Map<String, Object> toEventMap() {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("type", event);
        result.put("content", content);
        result.put("data", new LinkedHashMap<String, Object>(data));
        result.put("plugin", plugin);
        return result;
    }
}
