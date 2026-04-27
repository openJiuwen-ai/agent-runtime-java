package com.openjiuwen.a2a_service.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.a2a_service.common.RedisTaskStore;
import io.a2a.jsonrpc.common.json.JsonProcessingException;
import io.a2a.jsonrpc.common.json.JsonUtil;
import io.a2a.jsonrpc.common.wrappers.A2AErrorResponse;
import io.a2a.jsonrpc.common.wrappers.SendMessageResponse;
import io.a2a.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import io.a2a.spec.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/a2a")
public class A2ARouter {

    private static final Logger logger = LoggerFactory.getLogger(A2ARouter.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Executor executor;
    private final RedisTaskStore taskStore;
    private final AgentCard agentCard;

    public A2ARouter(Executor executor, RedisTaskStore taskStore) {
        this.executor = executor;
        this.taskStore = taskStore;
        this.agentCard = buildAgentCard();
    }

    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<String> getAgentCard() {
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(JsonUtil.toJson(agentCard));
        } catch (JsonProcessingException e) {
            logger.error("[A2A] AgentCard serialization failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    @PostMapping(value = {"/", ""}, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object handleJsonRpc(@RequestBody String body) {
        try {
            JsonNode root = MAPPER.readTree(body);
            Object id = parseId(root);
            String method = root.path("method").asText("");
            if ("message/stream".equals(method) || "SendStreamingMessage".equals(method)) {
                return handleStream(root, id);
            }
            if ("message/send".equals(method) || "SendMessage".equals(method)) {
                return handleSend(root, id);
            }
            return jsonResponse(new A2AErrorResponse(id,
                    new A2AError(-32601, "Method not found: " + method, null)));
        } catch (A2AError e) {
            return errorResponse(e);
        } catch (Exception e) {
            logger.error("[A2A] request failed", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("jsonrpc", "2.0", "error",
                            Map.of("code", -32603, "message", "Internal error")));
        }
    }

    private ResponseEntity<String> handleSend(JsonNode root, Object id) throws Exception {
        A2AInput input = parseInput(root);
        List<Map<String, Object>> events = new ArrayList<Map<String, Object>>();
        executor.execute(input.taskId, input.contextId, input.currentTask,
                input.query, input.headers, input.body, input.params, events::add);
        Task task = buildTask(input.taskId, input.contextId, finalState(input.taskId), input.message);
        return jsonResponse(new SendMessageResponse(id, task));
    }

    private SseEmitter handleStream(JsonNode root, Object id) throws Exception {
        A2AInput input = parseInput(root);
        SseEmitter emitter = new SseEmitter(600_000L);
        java.util.concurrent.Executors.newSingleThreadExecutor().submit(() -> {
            try {
                executor.execute(input.taskId, input.contextId, input.currentTask,
                        input.query, input.headers, input.body, input.params,
                        event -> sendStreamingEvent(emitter, id, input.taskId, input.contextId, event));
                sendStatus(emitter, id, input.taskId, input.contextId, finalState(input.taskId));
                emitter.complete();
            } catch (Exception e) {
                logger.error("[A2A] stream failed", e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    private void sendStreamingEvent(SseEmitter emitter, Object id, String taskId, String contextId, Map<String, Object> event) {
        try {
            StreamingEventKind item = toStreamingEvent(taskId, contextId, event);
            if (item != null) {
                emitter.send(JsonUtil.toJson(new SendStreamingMessageResponse(id, item)));
            }
        } catch (Exception e) {
            logger.warn("[A2A] send stream event failed", e);
        }
    }

    private void sendStatus(SseEmitter emitter, Object id, String taskId, String contextId, TaskState state) throws IOException {
        try {
            TaskStatusUpdateEvent status = TaskStatusUpdateEvent.builder()
                    .taskId(taskId)
                    .contextId(contextId)
                    .status(new TaskStatus(state))
                    .build();
            emitter.send(JsonUtil.toJson(new SendStreamingMessageResponse(id, status)));
        } catch (JsonProcessingException e) {
            throw new IOException(e);
        }
    }

    private StreamingEventKind toStreamingEvent(String taskId, String contextId, Map<String, Object> event) {
        if ("status_update".equals(event.get("type"))) {
            return TaskStatusUpdateEvent.builder()
                    .taskId(taskId)
                    .contextId(contextId)
                    .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
                    .build();
        }
        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        Object kind = event.get("_event_kind");
        if ("workflow".equals(kind)) {
            frame.put("event", event.getOrDefault("event", "message"));
            frame.put("data", event.getOrDefault("data", Map.of()));
        } else if ("agent".equals(kind)) {
            frame.put("type", event.getOrDefault("event", ""));
            frame.put("content", event.getOrDefault("content", ""));
            frame.put("plugin", event.getOrDefault("plugin", ""));
            frame.put("data", event.getOrDefault("data", Map.of()));
        } else {
            frame.putAll(event);
        }
        Artifact artifact = Artifact.builder()
                .artifactId(UUID.randomUUID().toString())
                .parts(List.of(new DataPart(frame)))
                .build();
        return TaskArtifactUpdateEvent.builder()
                .taskId(taskId)
                .contextId(contextId)
                .artifact(artifact)
                .lastChunk(Boolean.FALSE)
                .build();
    }

    private Task buildTask(String taskId, String contextId, TaskState state, Message message) {
        return Task.builder()
                .id(taskId)
                .contextId(contextId)
                .status(new TaskStatus(state))
                .history(message != null ? List.of(message) : List.of())
                .metadata(Map.of())
                .build();
    }

    private TaskState finalState(String taskId) {
        return taskStore.get(taskId)
                .map(RedisTaskStore.TaskInfo::getState)
                .orElse(TaskState.TASK_STATE_COMPLETED);
    }

    private A2AInput parseInput(JsonNode root) {
        JsonNode messageNode = root.path("params").path("message");
        String contextId = text(messageNode, "contextId", text(messageNode, "context_id", UUID.randomUUID().toString()));
        String taskId = text(messageNode, "taskId", text(messageNode, "task_id", UUID.randomUUID().toString()));
        String query = "";
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        List<Part<?>> parts = new ArrayList<Part<?>>();
        JsonNode partsNode = messageNode.path("parts");
        if (partsNode.isArray()) {
            for (JsonNode part : partsNode) {
                if (part.has("text") && query.isBlank()) {
                    query = part.get("text").asText("");
                    parts.add(new TextPart(query));
                }
                if (part.has("data")) {
                    data = jsonMap(part.get("data"));
                    parts.add(new DataPart(data));
                }
            }
        }
        Map<String, Object> body = asMap(data.get("body"));
        if (body.isEmpty()) {
            body = Map.of("input", Map.of("query", query), "stream", Boolean.TRUE);
        }
        Map<String, Object> headers = asMap(data.get("headers"));
        Map<String, Object> params = asMap(data.get("params"));
        RedisTaskStore.TaskInfo currentTask = taskStore.get(taskId).orElse(null);
        Message message = Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(text(messageNode, "messageId", text(messageNode, "message_id", UUID.randomUUID().toString())))
                .contextId(contextId)
                .taskId(taskId)
                .parts(parts.isEmpty() ? List.of(new TextPart(query)) : parts)
                .build();
        return new A2AInput(taskId, contextId, query, headers, body, params, currentTask, message);
    }

    private AgentCard buildAgentCard() {
        return AgentCard.builder()
                .name("EDPAgent")
                .description("Dynamic Planning ReAct Agent")
                .version("1.0.0")
                .capabilities(AgentCapabilities.builder()
                        .streaming(true)
                        .pushNotifications(false)
                        .build())
                .defaultInputModes(List.of("text/plain", "data"))
                .defaultOutputModes(List.of("text/plain", "data"))
                .skills(List.of(
                        AgentSkill.builder()
                                .id("edp_agent")
                                .name("EDPAgent")
                                .description("基金理财相关业务，包括余额查询、转账、理财推荐、购买确认。")
                                .tags(List.of("finance", "planning", "react"))
                                .inputModes(List.of("text/plain", "data"))
                                .outputModes(List.of("text/plain", "data"))
                                .build()
                ))
                .supportedInterfaces(List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), "/a2a/")))
                .build();
    }

    private ResponseEntity<String> jsonResponse(Object response) throws JsonProcessingException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(JsonUtil.toJson(response));
    }

    private ResponseEntity<String> errorResponse(A2AError error) {
        try {
            return jsonResponse(new A2AErrorResponse(error));
        } catch (JsonProcessingException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    private Object parseId(JsonNode root) {
        if (!root.has("id")) {
            return null;
        }
        JsonNode id = root.get("id");
        if (id.isNumber()) {
            return id.asLong();
        }
        return id.asText();
    }

    private String text(JsonNode node, String field, String defaultValue) {
        JsonNode value = node.get(field);
        return value != null && !value.isNull() ? value.asText(defaultValue) : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> jsonMap(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return Map.of();
        }
        try {
            return MAPPER.convertValue(node, Map.class);
        } catch (IllegalArgumentException ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> raw) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : raw.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
            return result;
        }
        return Map.of();
    }

    private record A2AInput(
            String taskId,
            String contextId,
            String query,
            Map<String, Object> headers,
            Map<String, Object> body,
            Map<String, Object> params,
            RedisTaskStore.TaskInfo currentTask,
            Message message
    ) {
    }
}
