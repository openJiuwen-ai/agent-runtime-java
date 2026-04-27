package com.openjiuwen.a2a_service.agents.EDPAgent.tool.todo;

import java.util.LinkedHashMap;
import java.util.Map;

public class TodoItem {

    private int index = 1;
    private String content = "";
    private TodoStatus status = TodoStatus.PENDING;
    private String activeForm = "";
    private String result;

    public int getIndex() {
        return index;
    }

    public void setIndex(int index) {
        this.index = index;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public TodoStatus getStatus() {
        return status;
    }

    public void setStatus(TodoStatus status) {
        this.status = status;
    }

    public String getActiveForm() {
        return activeForm;
    }

    public void setActiveForm(String activeForm) {
        this.activeForm = activeForm;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("index", index);
        data.put("content", content);
        data.put("status", status.getValue());
        data.put("activeForm", activeForm);
        data.put("result", result);
        return data;
    }

    public static TodoItem fromMap(Map<String, Object> data) {
        TodoItem item = new TodoItem();
        Object index = data.get("index");
        if (index instanceof Number number) {
            item.setIndex(number.intValue());
        }
        Object content = data.get("content");
        item.setContent(content != null ? String.valueOf(content) : "");
        Object status = data.get("status");
        if (status != null) {
            item.setStatus(TodoStatus.fromValue(String.valueOf(status)));
        }
        Object activeForm = data.get("activeForm");
        item.setActiveForm(activeForm != null ? String.valueOf(activeForm) : "");
        Object result = data.get("result");
        item.setResult(result != null ? String.valueOf(result) : null);
        return item;
    }
}
