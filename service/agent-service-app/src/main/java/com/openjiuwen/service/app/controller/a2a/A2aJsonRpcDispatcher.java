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
import com.openjiuwen.service.app.hosting.HostedAgentRuntime;
import com.openjiuwen.service.app.hosting.HostedIngressResolver;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.requesthandlers.RequestHandler;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AMethods;
import org.a2aproject.sdk.spec.EventKind;
import org.a2aproject.sdk.spec.InternalError;
import org.a2aproject.sdk.spec.InvalidParamsError;
import org.a2aproject.sdk.spec.MethodNotFoundError;
import org.a2aproject.sdk.spec.StreamingEventKind;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskIdParams;
import org.a2aproject.sdk.spec.TaskNotFoundError;
import org.a2aproject.sdk.spec.TaskQueryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Shared, transport-independent JSON-RPC dispatch over the existing SDK Task pipeline. */
public final class A2aJsonRpcDispatcher {
    private static final Logger log = LoggerFactory.getLogger(A2aJsonRpcDispatcher.class);

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Supplier<RequestHandler> requestHandler;

    private final ObjectProvider<TaskAdmissionGate> admissionGateProvider;

    private final A2AProperties a2aProperties;

    private final HostedIngressResolver hostedResolver;

    public A2aJsonRpcDispatcher(Supplier<RequestHandler> requestHandler,
            ObjectProvider<TaskAdmissionGate> admissionGateProvider, A2AProperties properties,
            HostedIngressResolver hostedResolver) {
        this.requestHandler = requestHandler;
        this.admissionGateProvider = admissionGateProvider;
        this.a2aProperties = properties;
        this.hostedResolver = hostedResolver;
    }

    /** Internal per-call routing data; no Servlet state is required. */
    public record DispatchContext(String agentId, ServerCallContext serverContext,
            Consumer<HostedAgentRuntime> onTargetSelected) { }

    public sealed interface DispatchResult permits Single, Stream { }

    /** HTTP compatibility hints are internal; method consumers always receive json. */
    public record Single(String json, int status, boolean jsonContentType, Object httpBody) implements DispatchResult { }

    public record Stream(Flow.Publisher<StreamingEventKind> publisher, Object requestId) implements DispatchResult { }

    public DispatchResult dispatch(String rawBody, DispatchContext context) {
        A2aJsonRpcProtocol.Request request;
        try {
            request = A2aJsonRpcProtocol.parseRequest(rawBody);
        } catch (A2aJsonRpcProtocol.RequestException error) {
            return errorResponse(error.getRequestId(), error.getError());
        }
        ServerCallContext ctx = context.serverContext();
        try {
            return dispatch(request.method(), request, request.id(), ctx, context);
        } catch (HostedIngressResolver.SelectionException error) {
            if (error.status() == 503 || hostedResolver == null) {
                return new Single(errorResponse(request.id(), (error.status() == 503 ? new InternalError("Agent is not ready")
                        : new InvalidParamsError(error.getMessage()))).json(),
                        error.status(), false, Map.of("type", "error", "error", error.getMessage()));
            }
            return errorResponse(request.id(), new InvalidParamsError(error.getMessage()));
        } catch (A2AError error) {
            releasePreAcquiredAdmission(ctx);
            return errorResponse(request.id(), error);
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException error) {
            releasePreAcquiredAdmission(ctx);
            log.error("A2A request failed: {}", error.getClass().getSimpleName());
            return errorResponse(request.id(), new InternalError("Internal error"));
        }
    }

    /** Encodes an ingress rejection while preserving the existing request-id parsing rules. */
    public static Single reject(String rawBody, A2AError error) {
        try {
            return errorResponse(A2aJsonRpcProtocol.parseRequest(rawBody).id(), error);
        } catch (A2aJsonRpcProtocol.RequestException invalid) {
            return errorResponse(invalid.getRequestId(), invalid.getError());
        }
    }

    public static Single errorResponse(Object id, A2AError error) {
        A2aJsonRpcProtocol.EncodedError encoded = A2aJsonRpcProtocol.errorResponse(id, error);
        return new Single(encoded.json(), encoded.status(), true, null);
    }

    /** Keeps SDK stream discriminators, unlike the historical GetTask response helper. */
    public static String streamingResponse(Object id, StreamingEventKind event)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + GSON.toJson(id)
                + ",\"result\":" + serializeA2aJson(event) + "}";
    }

    /**
     * Routes one parsed JSON-RPC request to its handler method.
     *
     * @param method the JSON-RPC method name
     * @param request the parsed JSON-RPC request
     * @param id the JSON-RPC request id
     * @param ctx the server call context
     * @param dispatchContext request-local target selection
     * @return the JSON-RPC response entity
     * @throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException when the
     *         response payload cannot be serialized
     */
    private DispatchResult dispatch(String method, A2aJsonRpcProtocol.Request request, Object id,
            ServerCallContext ctx, DispatchContext dispatchContext)
            throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        return switch (method) {
        case A2AMethods.SEND_MESSAGE_METHOD -> {
            ctx.getState().put("_a2a_stream", false);
            var params = A2aJsonRpcParamsParser.parseMessageSendParams(request.payload());
            validateInlinePushNotificationConfig(params);
            Optional<HostedAgentRuntime> target = selectTarget(dispatchContext, request.payload());
            target.ifPresent(runtime -> validateHostedTask(runtime, params.message()));
            if (isAdmissionRejected(ctx, params.message().contextId())) {
                yield admissionRejectedResponse(id);
            }
            EventKind result = selectedHandler(target).onMessageSend(params, ctx);
            yield new Single(serializeA2aJson(new SendMessageResponse(id, result)), 200, false, null);
        }
        case A2AMethods.SEND_STREAMING_MESSAGE_METHOD -> {
            ctx.getState().put("_a2a_stream", true);
            var params = A2aJsonRpcParamsParser.parseMessageSendParams(request.payload());
            validateInlinePushNotificationConfig(params);
            Optional<HostedAgentRuntime> target = selectTarget(dispatchContext, request.payload());
            target.ifPresent(runtime -> validateHostedTask(runtime, params.message()));
            if (isAdmissionRejected(ctx, params.message().contextId())) {
                yield admissionRejectedResponse(id);
            }
            Flow.Publisher<StreamingEventKind> pub = selectedHandler(target).onMessageSendStream(params, ctx);
            yield new Stream(pub, id);
        }
        case A2AMethods.GET_TASK_METHOD -> handleGetTask(request.payload(), id, ctx, dispatchContext);
        case A2AMethods.SUBSCRIBE_TO_TASK_METHOD -> handleSubscribeToTask(request.payload(), id, ctx, dispatchContext);
        default -> errorResponse(id,
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

    private static Single admissionRejectedResponse(Object id) {
        return new Single(admissionErrorBody(id), 503, true, null);
    }

    private DispatchResult handleGetTask(JsonObject request, Object id, ServerCallContext ctx,
            DispatchContext dispatchContext) {
        TaskQueryParams tqp = A2aJsonRpcParamsParser.parseTaskQueryParams(request);
        Task task = selectedHandler(selectTarget(dispatchContext, request)).onGetTask(tqp, ctx);
        return jsonRpcResponse(id, task);
    }

    private DispatchResult handleSubscribeToTask(JsonObject request, Object id, ServerCallContext ctx,
            DispatchContext dispatchContext) {
        TaskIdParams params = A2aJsonRpcParamsParser.parseTaskIdParams(request);
        Flow.Publisher<StreamingEventKind> publisher = selectedHandler(selectTarget(dispatchContext, request))
                .onSubscribeToTask(params, ctx);
        return new Stream(publisher, id);
    }

    private Optional<HostedAgentRuntime> selectTarget(DispatchContext context, JsonObject payload) {
        String agentId = context.agentId();
        if (hostedResolver == null) {
            if (agentId != null) {
                throw new HostedIngressResolver.SelectionException(404, "NOT_FOUND", "Not found");
            }
            return Optional.empty();
        }
        JsonElement tenant = payload.getAsJsonObject("params").get("tenant");
        if (tenant != null && !tenant.isJsonNull()
                && (!tenant.isJsonPrimitive() || !tenant.getAsJsonPrimitive().isString()
                        || !tenant.getAsString().isEmpty())) {
            throw new InvalidParamsError("Hosted runtime does not support tenant selection");
        }
        HostedAgentRuntime target = hostedResolver.resolveOrDefault(agentId);
        context.onTargetSelected().accept(target);
        return Optional.of(target);
    }

    private RequestHandler selectedHandler(Optional<HostedAgentRuntime> target) {
        return target.map(HostedAgentRuntime::requestHandler).orElseGet(requestHandler);
    }

    private static void validateHostedTask(HostedAgentRuntime target, org.a2aproject.sdk.spec.Message message) {
        if (message.taskId() == null || message.taskId().isEmpty()) {
            return;
        }
        Task task = target.taskStore().get(message.taskId());
        if (task == null) {
            throw new TaskNotFoundError();
        }
        if (message.contextId() != null && !message.contextId().equals(task.contextId())) {
            throw new InvalidParamsError("Task context does not match message context");
        }
    }

    private void validateInlinePushNotificationConfig(org.a2aproject.sdk.spec.MessageSendParams params) {
        if (params == null || params.configuration() == null
                || params.configuration().taskPushNotificationConfig() == null) {
            return;
        }
        A2aPushNotificationCallbackUrlPolicy.validateCallbackUrl(params.configuration().taskPushNotificationConfig(),
                a2aProperties == null ? java.util.List.of() : a2aProperties.getCallbackAllowedHosts());
    }

    /**
     * Builds a JSON-RPC success response, unwrapping the streaming event discriminator that {@link JsonUtil#toJson}
     * adds for {@link StreamingEventKind} / {@link EventKind} types.
     *
     * @param id the JSON-RPC request ID
     * @param result the result object to serialize
     * @return the JSON-RPC success response entity
     */
    private static Single jsonRpcResponse(Object id, Object result) {
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
            return new Single(response, 200, false, null);
        } catch (RuntimeException | org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException e) {
            log.error("Failed to serialize JSON-RPC response", e);
            return errorResponse(id, new InternalError("Internal error"));
        }
    }

    static String serializeA2aJson(Object value) throws org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException {
        return GSON.toJson(JsonParser.parseString(JsonUtil.toJson(value)));
    }

    private static String admissionErrorBody(Object id) {
        String idJson = id != null ? GSON.toJson(id) : "null";
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idJson
                + ",\"error\":{\"code\":-32603,\"message\":\"Service Unavailable: concurrent task limit reached\"}}";
    }

}
