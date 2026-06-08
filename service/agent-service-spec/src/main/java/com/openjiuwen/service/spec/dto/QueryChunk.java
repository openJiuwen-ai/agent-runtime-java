package com.openjiuwen.service.spec.dto;

import java.util.Map;

/**
 * Single SSE / stream chunk envelope.
 */
public class QueryChunk {

    private String type = "chunk";
    private Map<String, Object> data;

    public QueryChunk() {
    }

    public QueryChunk(String type, Map<String, Object> data) {
        this.type = type;
        this.data = data;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, Object> getData() {
        return data;
    }

    public void setData(Map<String, Object> data) {
        this.data = data;
    }
}
