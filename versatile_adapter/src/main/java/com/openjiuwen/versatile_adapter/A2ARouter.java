package com.openjiuwen.versatile_adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.a2a.jsonrpc.common.json.JsonProcessingException;
import io.a2a.jsonrpc.common.json.JsonUtil;
import io.a2a.jsonrpc.common.wrappers.*;
import io.a2a.server.ServerCallContext;
import io.a2a.server.requesthandlers.DefaultRequestHandler;
import io.a2a.spec.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.Flow;

/**
 * A2A 路由控制器 — 对应 Python app.py 中的 create_agent_card_routes + create_jsonrpc_routes。
 *
 * 暴露端点（A2A SDK 标准）：
 *   GET  /.well-known/agent-card.json  — AgentCard
 *   POST /                             — A2A JSON-RPC（message/send、message/stream 等）
 */
@RestController
public class A2ARouter {

    private static final Logger logger = LoggerFactory.getLogger(A2ARouter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** SDK PartTypeAdapter 只允许 text/file/data + metadata，多余 key 会触发 JsonSyntaxException */
    private static final Set<String> PART_ALLOWED_KEYS = Set.of("text", "file", "data", "metadata");

    private final AgentCard agentCard;
    private final DefaultRequestHandler requestHandler;

    public A2ARouter(AgentCard agentCard, DefaultRequestHandler requestHandler) {
        this.agentCard = agentCard;
        this.requestHandler = requestHandler;
    }

    // ── Agent Card ─────────────────────────────────────────────────────────

    @GetMapping("/.well-known/agent-card.json")
    public ResponseEntity<String> getAgentCard() {
        try {
            String json = JsonUtil.toJson(agentCard);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } catch (JsonProcessingException e) {
            logger.error("[A2ARouter] 序列化 AgentCard 失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    // ── JSON-RPC 入口 ─────────────────────────────────────────────────────

    @PostMapping(value = "/", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object handleJsonRpc(@RequestBody String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String method = root.has("method") ? root.get("method").asText() : "";

            Object id = parseId(root);

            // 流式方法必须直接返回 SseEmitter，不能包在 ResponseEntity 中
            if ("SendStreamingMessage".equals(method)) {
                return handleMessageStream(body, id);
            }

            return switch (method) {
                case "message/send" -> handleMessageSend(body, id);
                case "tasks/get" -> handleGetTask(body, id);
                case "tasks/cancel" -> handleCancelTask(body, id);
                case "tasks/list" -> handleListTasks(body, id);
                case "tasks/pushNotificationConfig/set" -> handleSetPushNotificationConfig(body, id);
                case "tasks/pushNotificationConfig/get" -> handleGetPushNotificationConfig(body, id);
                case "tasks/pushNotificationConfig/list" -> handleListPushNotificationConfig(body, id);
                case "tasks/pushNotificationConfig/delete" -> handleDeletePushNotificationConfig(body, id);
                default -> {
                    logger.warn("[A2ARouter] 未知方法: {}", method);
                    yield jsonResponse(new A2AErrorResponse(id,
                            new A2AError(-32601, "Method not found: " + method, null)));
                }
            };
        } catch (A2AError e) {
            logger.error("[A2ARouter] A2A 错误: {}", e.getMessage());
            return errorResponse(e);
        } catch (Exception e) {
            logger.error("[A2ARouter] 处理请求异常", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("jsonrpc", "2.0", "error",
                            Map.of("code", -32603, "message", "Internal error")));
        }
    }

    // ── 非流式方法 ─────────────────────────────────────────────────────────

    private ResponseEntity<String> handleMessageSend(String body, Object id) throws Exception {
        MessageSendParams params = extractMessageSendParams(body);
        EventKind result = requestHandler.onMessageSend(params, null);
        return jsonResponse(new SendMessageResponse(id, result));
    }

    private ResponseEntity<String> handleGetTask(String body, Object id) throws Exception {
        JsonNode params = extractParamsNode(body);
        TaskQueryParams queryParams = new TaskQueryParams(
                params.has("id") ? params.get("id").asText() : null,
                params.has("historyLength") ? params.get("historyLength").asInt() : null
        );
        Task result = requestHandler.onGetTask(queryParams, null);
        return jsonResponse(new GetTaskResponse(id, result));
    }

    private ResponseEntity<String> handleCancelTask(String body, Object id) throws Exception {
        JsonNode params = extractParamsNode(body);
        TaskIdParams taskIdParams = new TaskIdParams(
                params.has("id") ? params.get("id").asText() : null
        );
        Task result = requestHandler.onCancelTask(taskIdParams, null);
        return jsonResponse(new CancelTaskResponse(id, result));
    }

    private ResponseEntity<String> handleListTasks(String body, Object id) throws Exception {
        JsonNode params = extractParamsNode(body);
        ListTasksParams listParams = JsonUtil.fromJson(
                objectMapper.writeValueAsString(params), ListTasksParams.class);
        ListTasksResult result = requestHandler.onListTasks(listParams, null);
        return jsonResponse(new ListTasksResponse(id, result));
    }

    private ResponseEntity<String> handleSetPushNotificationConfig(String body, Object id) throws Exception {
        JsonNode params = extractParamsNode(body);
        TaskPushNotificationConfig config = JsonUtil.fromJson(
                objectMapper.writeValueAsString(params), TaskPushNotificationConfig.class);
        TaskPushNotificationConfig result = requestHandler.onCreateTaskPushNotificationConfig(config, null);
        return jsonResponse(new CreateTaskPushNotificationConfigResponse(id, result));
    }

    private ResponseEntity<String> handleGetPushNotificationConfig(String body, Object id) throws Exception {
        JsonNode params = extractParamsNode(body);
        GetTaskPushNotificationConfigParams getConfigParams = JsonUtil.fromJson(
                objectMapper.writeValueAsString(params), GetTaskPushNotificationConfigParams.class);
        TaskPushNotificationConfig result = requestHandler.onGetTaskPushNotificationConfig(getConfigParams, null);
        return jsonResponse(new GetTaskPushNotificationConfigResponse(id, result));
    }

    private ResponseEntity<String> handleListPushNotificationConfig(String body, Object id) throws Exception {
        JsonNode params = extractParamsNode(body);
        ListTaskPushNotificationConfigParams listConfigParams = JsonUtil.fromJson(
                objectMapper.writeValueAsString(params), ListTaskPushNotificationConfigParams.class);
        ListTaskPushNotificationConfigResult result = requestHandler.onListTaskPushNotificationConfig(listConfigParams, null);
        return jsonResponse(new ListTaskPushNotificationConfigResponse(id, result));
    }

    private ResponseEntity<String> handleDeletePushNotificationConfig(String body, Object id) throws Exception {
        JsonNode params = extractParamsNode(body);
        DeleteTaskPushNotificationConfigParams deleteConfigParams = JsonUtil.fromJson(
                objectMapper.writeValueAsString(params), DeleteTaskPushNotificationConfigParams.class);
        requestHandler.onDeleteTaskPushNotificationConfig(deleteConfigParams, null);
        return ResponseEntity.ok().build();
    }

    // ── 流式方法 ───────────────────────────────────────────────────────────

    private SseEmitter handleMessageStream(String body, Object id) throws Exception {
        MessageSendParams params = extractMessageSendParams(body);
        ServerCallContext context = new ServerCallContext(null, Map.of(), Set.of());
        Flow.Publisher<StreamingEventKind> publisher = requestHandler.onMessageSendStream(params, context);

        SseEmitter emitter = new SseEmitter(600_000L);

        publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(StreamingEventKind item) {
                try {
                    SendStreamingMessageResponse response = new SendStreamingMessageResponse(id, item);
                    String json = JsonUtil.toJson(response);
                    // SDK 的 TaskStatusUpdateEvent Record 包含 isFinal 字段，
                    // 但 Protobuf 的 TaskStatusUpdateEvent 没有该字段，
                    // Client SDK 通过 Protobuf 反序列化时会报错，需要移除。
                    if ("statusUpdate".equals(item.kind())) {
                        JsonNode tree = objectMapper.readTree(json);
                        removeIsFinal(tree);
                        json = objectMapper.writeValueAsString(tree);
                    }
                    String kind = item.kind();
                    logger.debug("[A2ARouter] stream item kind={}", kind);
                    emitter.send(json);
                    subscription.request(1);
                } catch (Exception e) {
                    logger.warn("[A2ARouter] SSE 发送异常", e);
                    subscription.cancel();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                logger.error("[A2ARouter] 流式处理错误", throwable);
                emitter.completeWithError(throwable);
            }

            @Override
            public void onComplete() {
                emitter.complete();
            }
        });

        return emitter;
    }

    // ── 工具方法 ───────────────────────────────────────────────────────────

    private Object parseId(JsonNode root) {
        if (!root.has("id")) return null;
        JsonNode idNode = root.get("id");
        if (idNode.isNumber()) return idNode.asLong();
        return idNode.asText();
    }

    private JsonNode extractParamsNode(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        return root.has("params") ? root.get("params") : objectMapper.createObjectNode();
    }

    /**
     * 使用 Gson + JsonUtil 解析 MessageSendParams（因为其中包含 Part 等复杂 protobuf 类型）。
     * 会清理 parts 中不属于 SDK 允许集合的额外字段（如 filename、mediaType），
     * 避免触发 PartTypeAdapter 的 "Part object must have one content key…" 校验。
     */
    private MessageSendParams extractMessageSendParams(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode paramsNode = root.has("params") ? root.get("params") : objectMapper.createObjectNode();

        // 清理 message.parts 中每个 Part 对象的多余字段
        JsonNode messageNode = paramsNode.has("message") ? paramsNode.get("message") : null;
        if (messageNode != null && messageNode.has("parts")) {
            JsonNode partsNode = messageNode.get("parts");
            if (partsNode.isArray()) {
                for (JsonNode part : partsNode) {
                    if (part.isObject()) {
                        ((ObjectNode) part).retain(PART_ALLOWED_KEYS);
                    }
                }
            }
        }
        String paramsJson = objectMapper.writeValueAsString(paramsNode);
        return JsonUtil.fromJson(paramsJson, MessageSendParams.class);
    }

    private ResponseEntity<String> jsonResponse(A2AResponse<?> response) {
        try {
            String json = JsonUtil.toJson(response);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } catch (JsonProcessingException e) {
            logger.error("[A2ARouter] 序列化响应失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private ResponseEntity<String> errorResponse(A2AError error) {
        try {
            String json = JsonUtil.toJson(new A2AErrorResponse(error));
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json);
        } catch (JsonProcessingException e) {
            logger.error("[A2ARouter] 序列化错误响应失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 递归移除 JSON 树中的 final / isFinal 字段。
     * Protobuf 的 TaskStatusUpdateEvent 不包含该字段，Client SDK 反序列化时会报错。
     */
    private void removeIsFinal(JsonNode node) {
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            obj.remove("final");
            obj.remove("isFinal");
            node.fields().forEachRemaining(entry -> removeIsFinal(entry.getValue()));
        } else if (node.isArray()) {
            node.forEach(this::removeIsFinal);
        }
    }
}
