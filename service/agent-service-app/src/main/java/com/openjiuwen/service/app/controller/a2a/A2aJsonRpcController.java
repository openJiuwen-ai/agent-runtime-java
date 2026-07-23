/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;
import com.openjiuwen.service.spec.paths.A2AServicePaths;
import com.openjiuwen.service.spec.security.AuthorizedResource;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.A2AMessage;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.UnauthenticatedUser;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.MessageSendParams;
import org.a2aproject.sdk.spec.MethodNotFoundError;
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

import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

import jakarta.servlet.http.HttpServletRequest;

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

    private static final Type METADATA_MAP_TYPE = new TypeToken<Map<String, Object>>() {
    }.getType();

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
    public ResponseEntity<?> handleJsonRpc(@RequestBody(required = false) String rawBody,
            HttpServletRequest servletRequest) {
        A2aJsonRpcProtocol.Request request;
        try {
            request = A2aJsonRpcProtocol.parseRequest(rawBody);
        } catch (A2aJsonRpcProtocol.RequestException e) {
            return A2aJsonRpcProtocol.errorResponse(e.getRequestId(), e.getError());
        }

        String method = request.method();
        Object id = request.id();
        ServerCallContext ctx = buildCallContext(servletRequest);

        try {
            return switch (method) {
                case A2AMethods.SEND_MESSAGE_METHOD -> {
                    ctx.getState().put("_a2a_stream", false);
                    var params = parseParams(request.payload());
                    EventKind result = requestHandler.onMessageSend(params, ctx);
                    yield ResponseEntity.ok(JsonUtil.toJson(new SendMessageResponse(id, result)));
                }
                case A2AMethods.SEND_STREAMING_MESSAGE_METHOD -> {
                    ctx.getState().put("_a2a_stream", true);
                    var params = parseParams(request.payload());
                    Flow.Publisher<StreamingEventKind> pub = requestHandler.onMessageSendStream(params, ctx);
                    yield streamToSse(pub, id);
                }
                case A2AMethods.GET_TASK_METHOD -> handleGetTask(request.payload(), id, ctx);
                default -> A2aJsonRpcProtocol.errorResponse(id,
                        new MethodNotFoundError(null, "Method not found: " + method, null));
            };
        } catch (A2AError e) {
            log.info("A2A protocol error: method={}, code={}, message={}", method, e.getCode(), e.getMessage());
            return A2aJsonRpcProtocol.errorResponse(id, e);
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            log.error("A2A request failed", e);
            return A2aJsonRpcProtocol.errorResponse(id, new InternalError("Internal error"));
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
                    String data = "{\"jsonrpc\":\"" + A2AMessage.JSONRPC_VERSION + "\",\"id\":" + idJson
                            + ",\"result\":" + eventJson + "}";
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

    private ResponseEntity<?> handleGetTask(JsonObject request, Object id, ServerCallContext ctx) {
        TaskQueryParams tqp = parseTaskQueryParams(request);
        Task task = requestHandler.onGetTask(tqp, ctx);
        return jsonRpcResponse(id, task);
    }

    static MessageSendParams parseParams(JsonObject request) {
        try {
            JsonObject params = request.getAsJsonObject("params");
            JsonObject m = params.getAsJsonObject("message");
            List<Part<?>> parts = parseParts(m);
            Message msg = buildMessage(m, parts);
            var sendParamsBuilder = MessageSendParams.builder().message(msg);
            Map<String, Object> paramsMetadata = parseMetadata(params);
            if (paramsMetadata != null) {
                sendParamsBuilder.metadata(paramsMetadata);
            }
            return sendParamsBuilder.build();
        } catch (JsonParseException | ClassCastException | IllegalStateException | IllegalArgumentException
                | NullPointerException | UnsupportedOperationException e) {
            log.debug("Invalid SendMessage params", e);
            throw new InvalidParamsError();
        }
    }

    private TaskQueryParams parseTaskQueryParams(JsonObject request) {
        try {
            JsonObject params = request.getAsJsonObject("params");
            return JsonUtil.fromJson(params.toString(), TaskQueryParams.class);
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            log.debug("Invalid GetTask params", e);
            throw new InvalidParamsError();
        }
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
                parts.add(new TextPart(text));
            }
        }
    }

    static Message buildMessage(JsonObject m, List<Part<?>> parts) {
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
                .metadata(parseMetadata(m)).build();
    }

    private static Map<String, Object> parseMetadata(JsonObject owner) {
        if (!owner.has("metadata") || owner.get("metadata").isJsonNull()) {
            return Map.of();
        }
        if (!owner.get("metadata").isJsonObject()) {
            throw new JsonParseException("metadata must be a JSON object");
        }
        return GSON.fromJson(owner.get("metadata"), METADATA_MAP_TYPE);
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
            return A2aJsonRpcProtocol.errorResponse(id, new InternalError("Internal error"));
        }
    }

    private ServerCallContext buildCallContext(HttpServletRequest req) {
        var ctx = new ServerCallContext(UnauthenticatedUser.INSTANCE,
                Map.of("remote-addr", req.getRemoteAddr(), "path", req.getRequestURI()), Set.of());
        ctx.getState().put(ServerCallContext.STRICT_CONTEXT_VALIDATION_KEY, false);
        return ctx;
    }
}
