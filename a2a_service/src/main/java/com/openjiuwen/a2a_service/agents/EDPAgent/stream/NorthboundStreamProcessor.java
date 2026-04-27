package com.openjiuwen.a2a_service.agents.EDPAgent.stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.session.interaction.InteractionOutput;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.singleagent.interrupt.ToolCallInterruptRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class NorthboundStreamProcessor {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TODOLIST_PATTERN = Pattern.compile("```todolist\\s*\\R(.*?)\\R```", Pattern.DOTALL);
    private static final Pattern TODO_UPDATE_PATTERN = Pattern.compile("```todo_update\\s*\\R(.*?)\\R```", Pattern.DOTALL);
    private static final Map<String, String> TODO_STATUS_CN = Map.of(
            "pending", "待执行",
            "in_progress", "执行中",
            "done", "完成",
            "failed", "失败"
    );

    private static final String STATE_IDLE = "idle";
    private static final String STATE_THINKING = "thinking";
    private static final String STATE_ANSWERING = "answering";

    private String state = STATE_IDLE;
    private String thinkBuffer = "";
    private String answerBuffer = "";
    private final Map<String, String> todoTitles = new LinkedHashMap<String, String>();
    private final Set<String> startedTodoIds = new HashSet<String>();

    public List<SseEvent> process(Object rawEvent) {
        if (!(rawEvent instanceof OutputSchema schema)) {
            return List.of();
        }

        String eventType = schema.getType();
        List<SseEvent> events = new ArrayList<SseEvent>();

        if ("__interaction__".equals(eventType)) {
            events.addAll(flushThinkingIfNeeded());
            events.addAll(flushAnswerIfNeeded());
            events.add(interruptStart(schema));
            return events;
        }

        Map<String, Object> payload = asMap(schema.getPayload());
        String content = resolveContent(payload);

        if ("llm_reasoning".equals(eventType)) {
            if (!STATE_THINKING.equals(state)) {
                events.addAll(flushAnswerIfNeeded());
                events.add(new SseEvent("think_start", "", Map.of(), ""));
                state = STATE_THINKING;
                thinkBuffer = "";
            }
            events.add(new SseEvent("think_chunk", content, Map.of(), ""));
            thinkBuffer += content;
            return events;
        }

        if ("llm_output".equals(eventType)) {
            events.addAll(flushThinkingIfNeeded());
            if (!STATE_ANSWERING.equals(state)) {
                events.add(new SseEvent("final_answer_start", "", Map.of(), ""));
                state = STATE_ANSWERING;
                answerBuffer = "";
            }
            events.add(new SseEvent("summary", content, Map.of(), ""));
            answerBuffer += content;
            return events;
        }

        if ("answer".equals(eventType)) {
            events.addAll(flushThinkingIfNeeded());
            if (STATE_ANSWERING.equals(state)) {
                events.add(new SseEvent("final_answer_chunk", answerBuffer, Map.of(), ""));
                events.add(new SseEvent("final_answer_end", answerBuffer, Map.of(), ""));
            } else {
                events.add(new SseEvent("final_answer_start", "", Map.of(), ""));
                events.add(new SseEvent("final_answer_chunk", content, Map.of(), ""));
                events.add(new SseEvent("final_answer_end", content, Map.of(), ""));
            }
            state = STATE_IDLE;
            answerBuffer = "";
            return events;
        }

        if ("tool_start".equals(eventType)) {
            events.addAll(flushThinkingIfNeeded());
            events.addAll(flushAnswerIfNeeded());
            String plugin = stringValue(payload.get("plugin"));
            Map<String, Object> args = asMap(payload.get("args"));
            events.add(new SseEvent("tool_start", content, args, plugin));
            events.add(new SseEvent("tool_status", content, Map.of(), plugin));
            return events;
        }

        if ("tool_end".equals(eventType)) {
            events.addAll(flushThinkingIfNeeded());
            events.addAll(flushAnswerIfNeeded());
            events.add(new SseEvent("tool_end", content, asMap(payload.get("data")), stringValue(payload.get("plugin"))));
            return events;
        }

        if ("error".equals(eventType)) {
            events.addAll(flushThinkingIfNeeded());
            events.addAll(flushAnswerIfNeeded());
            events.add(new SseEvent(
                    "error",
                    content,
                    Map.of(
                            "code", "AGENT_INTERNAL_ERROR",
                            "detail", content
                    ),
                    ""
            ));
        }

        return events;
    }

    public List<SseEvent> finalizeEvents() {
        List<SseEvent> events = new ArrayList<SseEvent>();
        events.addAll(flushThinkingIfNeeded());
        events.addAll(flushAnswerIfNeeded());
        return events;
    }

    private List<SseEvent> flushThinkingIfNeeded() {
        if (!STATE_THINKING.equals(state)) {
            return List.of();
        }
        List<SseEvent> events = new ArrayList<SseEvent>();
        events.addAll(parseTodoListBlocks(thinkBuffer));
        events.addAll(parseTodoUpdateBlocks(thinkBuffer));
        events.add(new SseEvent("think_end", "", Map.of(), ""));
        state = STATE_IDLE;
        thinkBuffer = "";
        return events;
    }

    private List<SseEvent> flushAnswerIfNeeded() {
        if (!STATE_ANSWERING.equals(state)) {
            return List.of();
        }
        List<SseEvent> events = List.of(
                new SseEvent("final_answer_chunk", answerBuffer, Map.of(), ""),
                new SseEvent("final_answer_end", answerBuffer, Map.of(), "")
        );
        state = STATE_IDLE;
        answerBuffer = "";
        return events;
    }

    private List<SseEvent> parseTodoListBlocks(String text) {
        List<SseEvent> events = new ArrayList<SseEvent>();
        Matcher matcher = TODOLIST_PATTERN.matcher(text);
        while (matcher.find()) {
            String body = matcher.group(1).trim();
            Object parsed;
            try {
                parsed = MAPPER.readValue(body, Object.class);
            } catch (Exception ignored) {
                continue;
            }
            if (!(parsed instanceof List<?> items)) {
                continue;
            }
            events.add(new SseEvent("todolist_start", "", Map.of(), ""));
            int count = 0;
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> todo = asMap(map);
                Object todoId = todo.get("id");
                String title = stringValue(todo.get("title"));
                String status = stringValue(todo.get("status"));
                String content = stringValue(todo.get("content"));
                if (content.isBlank()) {
                    content = String.valueOf(todoId) + "." + title + "（"
                            + TODO_STATUS_CN.getOrDefault(status, status) + "）<br/>";
                }
                events.add(new SseEvent(
                        "todolist_item",
                        content,
                        Map.of(
                                "id", todoId,
                                "title", title,
                                "status", status
                        ),
                        ""
                ));
                if (todoId != null) {
                    todoTitles.put(String.valueOf(todoId), title);
                }
                count++;
            }
            events.add(new SseEvent("todolist_end", "", Map.of("count", count), ""));
        }
        return events;
    }

    private List<SseEvent> parseTodoUpdateBlocks(String text) {
        List<SseEvent> events = new ArrayList<SseEvent>();
        Matcher matcher = TODO_UPDATE_PATTERN.matcher(text);
        while (matcher.find()) {
            String body = matcher.group(1).trim();
            Object parsed;
            try {
                parsed = MAPPER.readValue(body, Object.class);
            } catch (Exception ignored) {
                continue;
            }
            if (!(parsed instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> update = asMap(map);
            Object todoId = update.get("id");
            String idKey = todoId != null ? String.valueOf(todoId) : "";
            String status = stringValue(update.get("status"));
            String title = todoTitles.getOrDefault(idKey, "");

            if ("in_progress".equals(status) && !idKey.isBlank() && !startedTodoIds.contains(idKey)) {
                events.add(new SseEvent(
                        "todo_start",
                        title,
                        Map.of(
                                "id", todoId,
                                "title", title
                        ),
                        ""
                ));
                startedTodoIds.add(idKey);
            }

            events.add(new SseEvent(
                    "todo_status",
                    title,
                    Map.of(
                            "id", todoId,
                            "status", status
                    ),
                    ""
            ));
        }
        return events;
    }

    private SseEvent interruptStart(OutputSchema schema) {
        if (!(schema.getPayload() instanceof InteractionOutput interactionOutput)) {
            return new SseEvent("interrupt_start", "", Map.of(), "");
        }
        Object value = interactionOutput.getValue();
        String content = "";
        String plugin = "";
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        if (value instanceof ToolCallInterruptRequest request) {
            content = request.getMessage() != null ? request.getMessage() : "";
            plugin = request.getToolName() != null ? request.getToolName() : "";
            data.put("interrupt_id", interactionOutput.getId());
            data.put("context", request.getContext() != null ? request.getContext() : Map.of());
        }
        return new SseEvent("interrupt_start", content, data, plugin);
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                result.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return result;
        }
        return new LinkedHashMap<String, Object>();
    }

    private String resolveContent(Map<String, Object> payload) {
        Object output = payload.get("output");
        if (output instanceof Map<?, ?> outputMap) {
            Map<String, Object> nested = asMap(outputMap);
            Object nestedOutput = nested.get("output");
            if (nestedOutput instanceof Map<?, ?> nestedMap) {
                Map<String, Object> deeper = asMap(nestedMap);
                if (deeper.get("content") != null) {
                    return stringValue(deeper.get("content"));
                }
                if (deeper.get("output") != null) {
                    return stringValue(deeper.get("output"));
                }
            }
            if (nested.get("content") != null) {
                return stringValue(nested.get("content"));
            }
            if (nested.get("output") != null) {
                return stringValue(nested.get("output"));
            }
        }
        if (output != null) {
            return stringValue(output);
        }
        if (payload.get("content") != null) {
            return stringValue(payload.get("content"));
        }
        return "";
    }

    private String stringValue(Object value) {
        return value != null ? String.valueOf(value) : "";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }
}
