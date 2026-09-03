/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.adapters.common.concurrent.VirtualThreadSupport;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

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

    /**
     * Executor that drives A2A SSE subscription. On JDK 21 each subscription runs on its own
     * virtual thread; on JDK 17 a small daemon platform pool is used. This keeps the A2A
     * streaming path off {@code ForkJoinPool.commonPool()} so that long-lived subscriptions
     * do not starve the common pool and benefit from virtual-thread scaling.
     */
    private static final Executor SSE_EXECUTOR = createSseExecutor();

    private final RequestHandler requestHandler;

    private ObjectProvider<TaskAdmissionGate> admissionGateProvider;

    private A2AProperties a2aProperties;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setA2aProperties(A2AProperties a2aProperties) {
        this.a2aProperties = a2aProperties;
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
        // Content-Length pre-check (design FEAT-036 §2.2 step 1): reject oversized or
        // chunked bodies with HTTP 413 before JSON-RPC parsing; -1 disables the check.
        long maxMessageBytes = a2aProperties != null ? a2aProperties.getMaxMessageBytes()
                : com.openjiuwen.service.spec.part.A2aPartLimits.DEFAULT_MAX_REQUEST_BODY_BYTES;
        long contentLength = servletRequest.getContentLengthLong();
        if (maxMessageBytes >= 0 && (contentLength < 0 || contentLength > maxMessageBytes)) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE).build();
        }
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
            return dispatch(method, request, id, ctx);
        } catch (A2AError e) {
            releasePreAcquiredAdmission(ctx);
            log.info("A2A protocol error: method={}, code={}, message={}", method, e.getCode(), e.getMessage());
            return A2aJsonRpcProtocol.errorResponse(id, e);
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            releasePreAcquiredAdmission(ctx);
            log.error("A2A request failed", e);
            return A2aJsonRpcProtocol.errorResponse(id, new InternalError("Internal error"));
        }
    }

    /**
     * Routes one parsed JSON-RPC request to its handler method.
     *
     * @param method the JSON-RPC method name
     * @param request the parsed JSON-RPC request
     * @param id the JSON-RPC request id
     * @param ctx the server call context
     * @return the JSON-RPC response entity
     * @throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException when the
     *         response payload cannot be serialized
     */
    private ResponseEntity<?> dispatch(String method, A2aJsonRpcProtocol.Request request, Object id,
            ServerCallContext ctx) throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        return switch (method) {
        case A2AMethods.SEND_MESSAGE_METHOD -> {
            ctx.getState().put("_a2a_stream", false);
            var params = A2aJsonRpcParamsParser.parseMessageSendParams(request.payload());
            validateInlinePushNotificationConfig(params);
            if (isAdmissionRejected(ctx, params.message().contextId())) {
                yield admissionRejectedResponse(id);
            }
            EventKind result = requestHandler.onMessageSend(params, ctx);
            yield ResponseEntity.ok(serializeA2aJson(new SendMessageResponse(id, result)));
        }
        case A2AMethods.SEND_STREAMING_MESSAGE_METHOD -> {
            ctx.getState().put("_a2a_stream", true);
            var params = A2aJsonRpcParamsParser.parseMessageSendParams(request.payload());
            validateInlinePushNotificationConfig(params);
            if (isAdmissionRejected(ctx, params.message().contextId())) {
                yield admissionRejectedResponse(id);
            }
            Flow.Publisher<StreamingEventKind> pub = requestHandler.onMessageSendStream(params, ctx);
            yield streamToSse(pub, id);
        }
        case A2AMethods.GET_TASK_METHOD -> handleGetTask(request.payload(), id, ctx);
        case A2AMethods.SUBSCRIBE_TO_TASK_METHOD -> handleSubscribeToTask(request.payload(), id, ctx);
        default -> A2aJsonRpcProtocol.errorResponse(id,
                new MethodNotFoundError(null, "Method not found: " + method, null));
        };
    }

    /**
     * Authoritative admission at the transport entry. When a bounded gate is
     * configured, acquires a permit synchronously and marks the call context so
     * {@code A2AAgentExecutor} adopts the already-held permit instead of
     * acquiring a second one; the executor's {@code finally} block owns the
     * release. This closes the race window of the former read-only pre-check,
     * in which a request that slipped through was rejected inside the SDK
     * pipeline and surfaced as an asynchronous A2AError (HTTP 500 on the
     * streaming path) instead of a clean synchronous 503.
     *
     * @param ctx the server call context that carries the handover marker
     * @param conversationId the conversation identifier for rejection logging
     * @return {@code true} when the request must be rejected with HTTP 503
     */
    private boolean isAdmissionRejected(ServerCallContext ctx, String conversationId) {
        Optional<TaskAdmissionGate> admissionGate = admissionGate();
        if (admissionGate.isEmpty() || admissionGate.get().limit() < 0) {
            return false;
        }
        TaskAdmissionGate gate = admissionGate.get();
        if (gate.tryAcquire()) {
            ctx.getState().put(A2AAgentExecutor.PRE_ACQUIRED_ADMISSION_KEY, Boolean.TRUE);
            return false;
        }
        logRejected(conversationId);
        return true;
    }

    /**
     * Returns the pre-acquired permit when the request failed synchronously
     * before the agent executor adopted it (e.g. parameter validation inside
     * the SDK). The state-map removal is atomic, so exactly one of {this
     * controller, the executor's {@code finally}} releases the permit; on the
     * normal path the executor wins and this method is a no-op.
     *
     * @param ctx the server call context carrying the handover marker
     */
    private void releasePreAcquiredAdmission(ServerCallContext ctx) {
        if (ctx.getState().remove(A2AAgentExecutor.PRE_ACQUIRED_ADMISSION_KEY) == null) {
            return;
        }
        admissionGate().ifPresent(admissionGate -> {
            admissionGate.release();
            // The executor never adopted the permit (sync failure before agent
            // submission), so this release is not paired with task_released —
            // log it to keep gate-count changes traceable.
            log.warn("[CONCURRENCY] admission_returned reason=\"sync_failure_before_execution\" "
                    + "currentActive={} maxConcurrent={}", admissionGate.currentCount(), admissionGate.limit());
        });
    }

    /**
     * Resolves the admission gate bean, when configured.
     *
     * @return the gate wrapped as {@link Optional}; empty when no
     *         {@code TaskAdmissionGate} bean is available
     */
    private Optional<TaskAdmissionGate> admissionGate() {
        if (admissionGateProvider == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(admissionGateProvider.getIfAvailable());
    }

    private void logRejected(String conversationId) {
        admissionGate().ifPresent(gate -> log.warn(
                "[CONCURRENCY] task_rejected conversationId={} "
                        + "currentActive={} maxConcurrent={} reason=\"limit_reached\"",
                conversationId, gate.currentCount(), gate.limit()));
    }

    private static ResponseEntity<String> admissionRejectedResponse(Object id) {
        return ResponseEntity.status(503).contentType(MediaType.APPLICATION_JSON).body(admissionErrorBody(id));
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
                    log.error("A2A SSE event delivery failed requestId={}", requestId, ex);
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
                log.error("A2A SSE publisher failed requestId={}", requestId, t);
                emitter.completeWithError(t);
            }

            /**
             * Called when the stream completes normally.
             */
            public void onComplete() {
                emitter.complete();
            }
        }), SSE_EXECUTOR).whenComplete((ignored, failure) -> {
            if (failure != null) {
                log.error("A2A SSE subscription failed requestId={}", requestId, failure);
                // subscribe() may throw synchronously (e.g. executor rejection) before the
                // subscriber's onError is wired; without this the emitter never completes
                // and the SSE connection hangs until the client times out. Complete on an
                // already-completed emitter is a no-op, so this is safe on all paths.
                emitter.completeWithError(failure);
            }
        });
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

    /**
     * Builds the SSE subscription executor. On JDK 21+ uses a per-task virtual-thread executor so each
     * A2A streaming subscription runs on its own virtual thread; on JDK 17 falls back to a small
     * daemon platform-thread pool sized to the CPU count. The platform pool is unbounded-queue
     * cached-style: subscriptions are long-lived but few, and a cached pool avoids rejecting under
     * bursty load while still bounding peak threads by the subscription count.
     *
     * @return executor for driving {@code publisher.subscribe(...)}
     */
    private static Executor createSseExecutor() {
        if (VirtualThreadSupport.isSupported()) {
            return VirtualThreadSupport.newVirtualExecutor("a2a-sse",
                    (thread, error) -> log.error("Uncaught A2A SSE thread={}", thread.getName(), error));
        }
        AtomicInteger idx = new AtomicInteger();
        return Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "a2a-sse-" + idx.incrementAndGet());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((source, error) ->
                    log.error("Uncaught A2A SSE thread={}", source.getName(), error));
            return thread;
        });
    }
}
