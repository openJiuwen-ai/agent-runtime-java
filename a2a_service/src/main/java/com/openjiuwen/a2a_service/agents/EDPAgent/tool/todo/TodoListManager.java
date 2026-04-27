package com.openjiuwen.a2a_service.agents.EDPAgent.tool.todo;

import com.openjiuwen.core.session.Session;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TodoListManager {

    public static final String STATE_KEY = "todolist";

    public List<TodoItem> load(Session session) {
        List<TodoItem> result = new ArrayList<TodoItem>();
        Object data = session.getState(STATE_KEY);
        if (!(data instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (item instanceof TodoItem todoItem) {
                result.add(todoItem);
            } else if (item instanceof Map<?, ?> map) {
                Map<String, Object> normalized = new LinkedHashMap<String, Object>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                result.add(TodoItem.fromMap(normalized));
            }
        }
        return result;
    }

    public void save(Session session, List<TodoItem> todoList) {
        List<Map<String, Object>> serialized = new ArrayList<Map<String, Object>>();
        for (TodoItem item : todoList) {
            serialized.add(item.toMap());
        }
        session.updateState(Map.of(STATE_KEY, serialized));
    }

    public List<TodoItem> createTodoList(Session session, List<String> contents, boolean activateFirst) {
        List<TodoItem> todoList = load(session);
        int startIndex = todoList.size() + 1;
        List<TodoItem> created = new ArrayList<TodoItem>();
        for (int i = 0; i < contents.size(); i++) {
            TodoItem item = new TodoItem();
            item.setIndex(startIndex + i);
            item.setContent(contents.get(i));
            item.setStatus(TodoStatus.PENDING);
            todoList.add(item);
            created.add(item);
        }
        if (activateFirst && !created.isEmpty() && getInProgressTask(session) == null) {
            created.get(0).setStatus(TodoStatus.IN_PROGRESS);
        }
        save(session, todoList);
        return created;
    }

    public TodoItem getTaskByIndex(Session session, int index) {
        for (TodoItem item : load(session)) {
            if (item.getIndex() == index) {
                return item;
            }
        }
        return null;
    }

    public TodoItem getInProgressTask(Session session) {
        for (TodoItem item : load(session)) {
            if (item.getStatus() == TodoStatus.IN_PROGRESS) {
                return item;
            }
        }
        return null;
    }

    public List<TodoItem> getTasksByStatus(Session session, TodoStatus status, boolean includeCompleted) {
        List<TodoItem> result = new ArrayList<TodoItem>();
        for (TodoItem item : load(session)) {
            if (item.getStatus() == status) {
                result.add(item);
            } else if (includeCompleted && status == TodoStatus.COMPLETED && item.getStatus() == TodoStatus.COMPLETED) {
                result.add(item);
            }
        }
        return result;
    }

    public boolean deleteTask(Session session, int index) {
        List<TodoItem> todoList = load(session);
        boolean removed = todoList.removeIf(item -> item.getIndex() == index);
        if (!removed) {
            return false;
        }
        for (int i = 0; i < todoList.size(); i++) {
            todoList.get(i).setIndex(i + 1);
        }
        save(session, todoList);
        return true;
    }

    public boolean canStartTask(Session session, int index) {
        if (index == 1) {
            return true;
        }
        for (TodoItem item : load(session)) {
            if (item.getIndex() < index && item.getStatus() != TodoStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }
}
