/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openjiuwen.service.spec.paths.A2AServicePaths;
import jakarta.servlet.http.HttpServletRequest;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.*;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.*;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

@RestController
public class A2aJsonRpcController {

    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcController.class);
    private static final Gson GSON = new Gson();
    private final RequestHandler requestHandler;

    public A2aJsonRpcController(RequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    @PostMapping({A2AServicePaths.A2A_JSONRPC, A2AServicePaths.A2A_JSONRPC_NO_SLASH})
    public ResponseEntity<?> handleJsonRpc(@RequestBody String rawBody,
                                            HttpServletRequest servletRequest) {
        String method;
        Object id;
        try {
            JsonObject root = JsonParser.parseString(rawBody).getAsJsonObject();
            method = root.has("method") ? root.get("method").getAsString() : "";
            id = root.has("id") ? GSON.fromJson(root.get("id"), Object.class) : null;
        } catch (Exception e) {
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
                case "GetTask" -> {
                    JsonObject p = JsonParser.parseString(rawBody).getAsJsonObject().getAsJsonObject("params");
                    TaskQueryParams tqp = JsonUtil.fromJson(p.toString(), TaskQueryParams.class);
                    Task task = requestHandler.onGetTask(tqp, ctx);
                    yield jsonRpcResponse(id, task);
                }
                default -> badRequest(new A2AError(-32601, "Method not found: " + method, Map.of()), id);
            };
        } catch (Exception e) {
            log.error("A2A request failed", e);
            return badRequest(new A2AError(-32603, "Internal error", Map.of()), id);
        }
    }

    private ResponseEntity<SseEmitter> streamToSse(Flow.Publisher<StreamingEventKind> publisher,
                                                     Object requestId) {
        String idJson = GSON.toJson(requestId);
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() ->
            publisher.subscribe(new Flow.Subscriber<>() {
                private Flow.Subscription sub;
                public void onSubscribe(Flow.Subscription s) { sub = s; s.request(1); }
                public void onNext(StreamingEventKind e) {
                    try {
                        String eventJson = JsonUtil.toJson(e);
                        String data = "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
                                + ",\"result\":" + eventJson + "}";
                        emitter.send(SseEmitter.event().name("jsonrpc").data(data));
                        sub.request(1);
                    } catch (Exception ex) { sub.cancel(); emitter.completeWithError(ex); }
                }
                public void onError(Throwable t) { emitter.completeWithError(t); }
                public void onComplete() { emitter.complete(); }
            })
        );
        emitter.onTimeout(emitter::complete);
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    private MessageSendParams parseParams(String rawBody) throws Exception {
        JsonObject root = JsonParser.parseString(rawBody).getAsJsonObject();
        JsonObject params = root.getAsJsonObject("params");
        JsonObject m = params.getAsJsonObject("message");
        // Build Message manually — SDK Gson type adapters expect wrappers for Records
        String roleStr = (m.has("role") && !m.get("role").isJsonNull()
                && !m.get("role").getAsString().isBlank())
                ? m.get("role").getAsString() : "ROLE_USER";
        Message.Role role = Message.Role.valueOf(roleStr);
        List<Part<?>> parts = new java.util.ArrayList<>();
        if (m.has("parts")) {
            for (var el : m.getAsJsonArray("parts")) {
                var obj = el.getAsJsonObject();
                if (obj.has("text") && !obj.get("text").isJsonNull()) {
                    String text = obj.get("text").getAsString();
                    if (!text.isBlank()) parts.add(new TextPart(text));
                }
            }
        }
        String rawMessageId = (m.has("messageId") && !m.get("messageId").isJsonNull())
                ? m.get("messageId").getAsString() : null;
        String messageId = (rawMessageId != null && !rawMessageId.isBlank()) ? rawMessageId : null;
        String rawContextId = (m.has("contextId") && !m.get("contextId").isJsonNull())
                ? m.get("contextId").getAsString() : null;
        String contextId = (rawContextId != null && !rawContextId.isBlank()) ? rawContextId : null;
        String rawTaskId = (m.has("taskId") && !m.get("taskId").isJsonNull())
                ? m.get("taskId").getAsString() : null;
        String taskId = (rawTaskId != null && !rawTaskId.isBlank()) ? rawTaskId : null;
        Message msg = Message.builder().role(role).parts(parts)
                .contextId(contextId).taskId(taskId).messageId(messageId).build();
        var sendParamsBuilder = MessageSendParams.builder().message(msg);
        // Preserve metadata from A2A protocol message
        if (params.has("metadata")) {
            sendParamsBuilder.metadata(GSON.fromJson(params.get("metadata"), Map.class));
        }
        return sendParamsBuilder.build();
    }

    /**
     * Builds a JSON-RPC success response, unwrapping the streaming event
     * discriminator that {@link JsonUtil#toJson} adds for
     * {@link StreamingEventKind} / {@link EventKind} types.
     */
    private static ResponseEntity<String> jsonRpcResponse(Object id, Object result) {
        try {
            String resultJson = JsonUtil.toJson(result);
            // StreamingEventKindTypeAdapter wraps as {"task":{...}} — unwrap it
            JsonObject obj = JsonParser.parseString(resultJson).getAsJsonObject();
            if (obj.size() == 1) {
                String key = obj.keySet().iterator().next();
                if ("task".equals(key) || "message".equals(key)
                        || "statusUpdate".equals(key) || "artifactUpdate".equals(key)) {
                    resultJson = obj.get(key).toString();
                }
            }
            String idPart = id != null ? ",\"id\":" + JsonUtil.toJson(id) : "";
            String response = "{\"jsonrpc\":\"2.0\"" + idPart
                    + ",\"result\":" + resultJson + "}";
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Failed to serialize JSON-RPC response", e);
            return ResponseEntity.internalServerError()
                    .body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603}}");
        }
    }

    private ServerCallContext buildCallContext(HttpServletRequest req) {
        var ctx = new ServerCallContext(UnauthenticatedUser.INSTANCE,
                Map.of("remote-addr", req.getRemoteAddr(), "path", req.getRequestURI()), Set.of());
        ctx.getState().put(ServerCallContext.STRICT_CONTEXT_VALIDATION_KEY, false);
        return ctx;
    }

    private ResponseEntity<String> badRequest(A2AError error, Object id) {
        try { return ResponseEntity.badRequest().body(JsonUtil.toJson(new A2AErrorResponse(id, error))); }
        catch (Exception e) { return ResponseEntity.badRequest().body("{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603}}"); }
    }
}
