/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
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
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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

/**
 * JSON-RPC controller for A2A protocol endpoints. Handles {@code SendMessage}, {@code SendStreamingMessage},
 * {@code GetTask}, and {@code SubscribeToTask} methods.
 *
 * @since 0.1.0
 */
@RestController
public class A2aJsonRpcController {
    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcController.class);

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final RequestHandler requestHandler;

    private ObjectProvider<TaskAdmissionGate> admissionGateProvider;

    /**
     * Constructs the JSON-RPC controller.
     *
     * @param requestHandler the A2A SDK request handler
     */
    public A2aJsonRpcController(RequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setAdmissionGateProvider(ObjectProvider<TaskAdmissionGate> admissionGateProvider) {
        this.admissionGateProvider = admissionGateProvider;
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
            jakarta.servlet.http.HttpServletRequest servletRequest) {
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
                    var params = A2aJsonRpcParamsParser.parseMessageSendParams(request.payload());
                    validateInlinePushNotificationConfig(params);
                    if (isAdmissionOverloaded()) {
                        logRejected(params.message().contextId());
                        yield ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON)
                                .body(admissionErrorBody(id));
                    }
                    EventKind result = requestHandler.onMessageSend(params, ctx);
                    yield ResponseEntity.ok(serializeA2aJson(new SendMessageResponse(id, result)));
                }
                case A2AMethods.SEND_STREAMING_MESSAGE_METHOD -> {
                    ctx.getState().put("_a2a_stream", true);
                    var params = A2aJsonRpcParamsParser.parseMessageSendParams(request.payload());
                    validateInlinePushNotificationConfig(params);
                    if (isAdmissionOverloaded()) {
                        logRejected(params.message().contextId());
                        yield ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON)
                                .body(admissionErrorBody(id));
                    }
                    Flow.Publisher<StreamingEventKind> pub = requestHandler.onMessageSendStream(params, ctx);
                    yield streamToSse(pub, id);
                }
                case A2AMethods.GET_TASK_METHOD -> handleGetTask(request.payload(), id, ctx);
                case A2AMethods.SUBSCRIBE_TO_TASK_METHOD -> handleSubscribeToTask(request.payload(), id, ctx);
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

    /**
     * Read-only admission pre-check for fast failure (HTTP 503 without entering
     * the SDK pipeline). Authoritative admission happens in
     * {@code A2AAgentExecutor.executeRequest()} — this check carries no permit
     * and therefore has no release obligation; requests that slip through the
     * race window are rejected there as a clean FAILED task.
     */
    private boolean isAdmissionOverloaded() {
        if (admissionGateProvider == null) {
            return false;
        }
        TaskAdmissionGate admissionGate = admissionGateProvider.getIfAvailable();
        return admissionGate != null && admissionGate.limit() >= 0
                && admissionGate.currentCount() >= admissionGate.limit();
    }

    private void logRejected(String conversationId) {
        TaskAdmissionGate gate = admissionGateProvider != null ? admissionGateProvider.getIfAvailable() : null;
        if (gate != null) {
            log.warn("[CONCURRENCY] task_rejected conversationId={} currentActive={} maxConcurrent={} reason=\"limit_reached\"",
                    conversationId, gate.currentCount(), gate.limit());
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
                    String eventJson = serializeA2aJson(e);
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
        TaskQueryParams tqp = A2aJsonRpcParamsParser.parseTaskQueryParams(request);
        Task task = requestHandler.onGetTask(tqp, ctx);
        return jsonRpcResponse(id, task);
    }

    private ResponseEntity<SseEmitter> handleSubscribeToTask(JsonObject request, Object id, ServerCallContext ctx) {
        TaskIdParams params = A2aJsonRpcParamsParser.parseTaskIdParams(request);
        Flow.Publisher<StreamingEventKind> publisher = requestHandler.onSubscribeToTask(params, ctx);
        return streamToSse(publisher, id);
    }

    private void validateInlinePushNotificationConfig(org.a2aproject.sdk.spec.MessageSendParams params) {
        if (params == null || params.configuration() == null
                || params.configuration().taskPushNotificationConfig() == null) {
            return;
        }
        A2aPushNotificationCallbackUrlPolicy.validateCallbackUrl(params.configuration().taskPushNotificationConfig());
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
            JsonElement resultElement = JsonParser.parseString(JsonUtil.toJson(result));
            // StreamingEventKindTypeAdapter wraps as {"task":{...}} — unwrap it
            JsonObject obj = resultElement.getAsJsonObject();
            if (obj.size() == 1) {
                String key = obj.keySet().iterator().next();
                if ("task".equals(key) || "message".equals(key) || "statusUpdate".equals(key)
                        || "artifactUpdate".equals(key)) {
                    resultElement = obj.get(key);
                }
            }
            String resultJson = GSON.toJson(resultElement);
            String idPart = id != null ? ",\"id\":" + GSON.toJson(id) : "";
            String response = "{\"jsonrpc\":\"2.0\"" + idPart + ",\"result\":" + resultJson + "}";
            return ResponseEntity.ok(response);
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            log.error("Failed to serialize JSON-RPC response", e);
            return A2aJsonRpcProtocol.errorResponse(id, new InternalError("Internal error"));
        }
    }

    static String serializeA2aJson(Object value) throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        return GSON.toJson(JsonParser.parseString(JsonUtil.toJson(value)));
    }

    private ServerCallContext buildCallContext(jakarta.servlet.http.HttpServletRequest req) {
        var ctx = new ServerCallContext(UnauthenticatedUser.INSTANCE,
                Map.of("remote-addr", req.getRemoteAddr(), "path", req.getRequestURI()), Set.of());
        ctx.getState().put(ServerCallContext.STRICT_CONTEXT_VALIDATION_KEY, false);
        Map<String, String> ingressHeaders = A2AMessageContext.tenantHeadersFrom(req);
        if (!ingressHeaders.isEmpty()) {
            ctx.getState().put(A2AMessageContext.INGRESS_HEADERS_STATE_KEY, ingressHeaders);
        }
        return ctx;
    }

    private static String admissionErrorBody(Object id) {
        String idJson = id != null ? GSON.toJson(id) : "null";
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
                + ",\"error\":{\"code\":-32603,\"message\":\"Service Unavailable: concurrent task limit reached\"}}";
    }
}
