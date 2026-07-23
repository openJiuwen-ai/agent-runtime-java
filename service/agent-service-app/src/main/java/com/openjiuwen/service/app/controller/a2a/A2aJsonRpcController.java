/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.openjiuwen.service.spec.paths.A2AServicePaths;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import jakarta.servlet.http.HttpServletRequest;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AErrorResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/**
 * JSON-RPC controller for A2A protocol endpoints. Handles {@code SendMessage}, {@code SendStreamingMessage}, and
 * {@code GetTask} methods.
 *
 * @since 0.1.0
 */
@RestController
public class A2aJsonRpcController {
    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcController.class);

    private static final Gson GSON = new Gson();

    private final RequestHandler requestHandler;

    /**
     * Constructs the JSON-RPC controller.
     *
     * @param requestHandler the A2A SDK request handler
     */
    public A2aJsonRpcController(RequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    /**
     * Handles all A2A JSON-RPC requests via {@code POST} on the standard and no-slash paths.
     *
     * @param rawBody the raw JSON-RPC request body
     * @param servletRequest the HTTP servlet request
     * @return the JSON-RPC response entity
     */
    @PostMapping({A2AServicePaths.A2A_JSONRPC, A2AServicePaths.A2A_JSONRPC_NO_SLASH})
    @AuthorizedResource(resource = "a2a", action = "rpc")
    public ResponseEntity<?> handleJsonRpc(@RequestBody String rawBody, HttpServletRequest servletRequest) {
        String method;
        Object id;
        try {
            JsonObject root = JsonParser.parseString(rawBody).getAsJsonObject();
            method = root.has("method") ? root.get("method").getAsString() : "";
            id = root.has("id") ? GSON.fromJson(root.get("id"), Object.class) : null;
        } catch (JsonSyntaxException | IllegalStateException e) {
            return badRequest(new A2AError(-32700, "Parse error", Map.of()), null);
        }
        ServerCallContext ctx = buildCallContext(servletRequest);

        try {
            return switch (method) {
                case "SendMessage" -> {
                    ctx.getState().put("_a2a_stream", false);
                    var params = parseParams(rawBody);
                    EventKind result = requestHandler.onMessageSend(params, ctx);
                    yield ResponseEntity.ok(JsonUtil.toJson(new SendMessageResponse(id, result)));
                }
                case "SendStreamingMessage" -> {
                    ctx.getState().put("_a2a_stream", true);
                    var params = parseParams(rawBody);
                    Flow.Publisher<StreamingEventKind> pub = requestHandler.onMessageSendStream(params, ctx);
                    yield streamToSse(pub, id);
                }
                case "GetTask" -> handleGetTask(rawBody, id, ctx);
                default -> badRequest(new A2AError(-32601, "Method not found: " + method, Map.of()), id);
            };
        } catch (A2AError e) {
            log.info("A2A protocol error: method={}, code={}, message={}", method, e.getCode(), e.getMessage());
            return jsonRpcError(id, e);
        } catch (IllegalStateException | IllegalArgumentException | NullPointerException e) {
            log.error("A2A request failed", e);
            return badRequest(new A2AError(-32603, "Internal error", Map.of()), id);
        } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            log.error("A2A JSON parse failed", e);
            return badRequest(new A2AError(-32700, "Parse error", Map.of()), id);
        }
    }

    private ResponseEntity<SseEmitter> streamToSse(Flow.Publisher<StreamingEventKind> publisher, Object requestId) {
        String idJson = GSON.toJson(requestId);
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> publisher.subscribe(new Flow.Subscriber<>() {
            private Flow.Subscription sub;

            /**
             * Called when the subscription is established.
             *
             * @param s the flow subscription
             */
            public void onSubscribe(Flow.Subscription s) {
                sub = s;
                s.request(1);
            }

            /**
             * Called when a new streaming event is received.
             *
             * @param e the streaming event
             */
            public void onNext(StreamingEventKind e) {
                try {
                    String eventJson = JsonUtil.toJson(e);
                    String data = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson + ",\"result\":" + eventJson + "}";
                    emitter.send(SseEmitter.event().name("jsonrpc").data(data));
                    sub.request(1);
                } catch (org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException | java.io.IOException
                        | RuntimeException ex) {
                    sub.cancel();
                    emitter.completeWithError(ex);
                }
            }

            /**
             * Called when the stream encounters an error.
             *
             * @param t the error
             */
            public void onError(Throwable t) {
                emitter.completeWithError(t);
            }

            /**
             * Called when the stream completes normally.
             */
            public void onComplete() {
                emitter.complete();
            }
        }));
        emitter.onTimeout(emitter::complete);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private ResponseEntity<?> handleGetTask(String rawBody, Object id, ServerCallContext ctx)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        JsonObject p = JsonParser.parseString(rawBody).getAsJsonObject().getAsJsonObject("params");
        TaskQueryParams tqp = JsonUtil.fromJson(p.toString(), TaskQueryParams.class);
        Task task = requestHandler.onGetTask(tqp, ctx);
        return jsonRpcResponse(id, task);
    }

    private MessageSendParams parseParams(String rawBody) {
        JsonObject root = JsonParser.parseString(rawBody).getAsJsonObject();
        JsonObject params = root.getAsJsonObject("params");
        JsonObject m = params.getAsJsonObject("message");
        List<Part<?>> parts = parseParts(m);
        Message msg = buildMessage(m, parts);
        var sendParamsBuilder = MessageSendParams.builder().message(msg);
        if (params.has("metadata")) {
            sendParamsBuilder.metadata(GSON.fromJson(params.get("metadata"), Map.class));
        }
        return sendParamsBuilder.build();
    }

    private static List<Part<?>> parseParts(JsonObject m) {
        List<Part<?>> parts = new java.util.ArrayList<>();
        if (m.has("parts")) {
            for (var el : m.getAsJsonArray("parts")) {
                var obj = el.getAsJsonObject();
                extractTextPart(obj, parts);
            }
        }
        return parts;
    }

    private static void extractTextPart(JsonObject obj, List<Part<?>> parts) {
        if (obj.has("text") && !obj.get("text").isJsonNull()) {
            String text = obj.get("text").getAsString();
            if (!text.isBlank()) {
                if (obj.has("metadata") && obj.get("metadata").isJsonObject()) {
                    @SuppressWarnings("unchecked") Map<String, Object> metadata =
                        GSON.fromJson(obj.get("metadata"), Map.class);
                    parts.add(new TextPart(text, metadata));
                } else {
                    parts.add(new TextPart(text));
                }
            }
        }
    }

    private static Message buildMessage(JsonObject m, List<Part<?>> parts) {
        String roleStr = (m.has("role") && !m.get("role").isJsonNull() && !m.get("role").getAsString().isBlank())
                ? m.get("role").getAsString()
                : "ROLE_USER";
        Message.Role role = Message.Role.valueOf(roleStr);
        String rawMessageId = m.has("messageId") && !m.get("messageId").isJsonNull()
                ? m.get("messageId").getAsString()
                : null;
        String rawContextId = m.has("contextId") && !m.get("contextId").isJsonNull()
                ? m.get("contextId").getAsString()
                : null;
        String messageId = (rawMessageId != null && !rawMessageId.isBlank()) ? rawMessageId : null;
        String contextId = (rawContextId != null && !rawContextId.isBlank()) ? rawContextId : null;
        String rawTaskId = m.has("taskId") && !m.get("taskId").isJsonNull() ? m.get("taskId").getAsString() : null;
        String taskId = (rawTaskId != null && !rawTaskId.isBlank()) ? rawTaskId : null;
        return Message.builder().role(role).parts(parts).contextId(contextId).taskId(taskId).messageId(messageId)
                .build();
    }

    /**
     * Builds a JSON-RPC success response, unwrapping the streaming event discriminator that {@link JsonUtil#toJson}
     * adds for {@link StreamingEventKind} / {@link EventKind} types.
     *
     * @param id the JSON-RPC request ID
     * @param result the result object to serialize
     * @return the JSON-RPC success response entity
     */
    private static ResponseEntity<String> jsonRpcResponse(Object id, Object result) {
        try {
            String resultJson = JsonUtil.toJson(result);
            // StreamingEventKindTypeAdapter wraps as {"task":{...}} — unwrap it
            JsonObject obj = JsonParser.parseString(resultJson).getAsJsonObject();
            if (obj.size() == 1) {
                String key = obj.keySet().iterator().next();
                if ("task".equals(key) || "message".equals(key) || "statusUpdate".equals(key)
                        || "artifactUpdate".equals(key)) {
                    resultJson = obj.get(key).toString();
                }
            }
            String idPart = id != null ? ",\"id\":" + JsonUtil.toJson(id) : "";
            String response = "{\"jsonrpc\":\"2.0\"" + idPart + ",\"result\":" + resultJson + "}";
            return ResponseEntity.ok(response);
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            log.error("Failed to serialize JSON-RPC response", e);
            return ResponseEntity.internalServerError().body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603}}");
        }
    }

    private ServerCallContext buildCallContext(HttpServletRequest req) {
        var ctx = new ServerCallContext(UnauthenticatedUser.INSTANCE,
                Map.of("remote-addr", req.getRemoteAddr(), "path", req.getRequestURI()), Set.of());
        ctx.getState().put(ServerCallContext.STRICT_CONTEXT_VALIDATION_KEY, false);
        return ctx;
    }

    private static ResponseEntity<String> jsonRpcError(Object id, A2AError error) {
        try {
            return ResponseEntity.ok(JsonUtil.toJson(new A2AErrorResponse(id, error)));
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            log.error("Failed to serialize A2A error response", e);
            return ResponseEntity.internalServerError().body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603}}");
        }
    }

    private ResponseEntity<String> badRequest(A2AError error, Object id) {
        try {
            return ResponseEntity.badRequest().body(JsonUtil.toJson(new A2AErrorResponse(id, error)));
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            return ResponseEntity.badRequest().body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603}}");
        }
    }
}
