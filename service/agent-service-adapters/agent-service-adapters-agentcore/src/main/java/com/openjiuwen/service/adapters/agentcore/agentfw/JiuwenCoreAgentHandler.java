/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import com.openjiuwen.core.common.schema.BaseCard;
import com.openjiuwen.core.controller.schema.ControllerOutput;
import com.openjiuwen.core.memory.external.MemoryProvider;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.interaction.InteractionOutput;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.session.stream.TraceSchema;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolCallInterruptRequest;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default {@link AgentHandler} for OpenJiuwen agent-core-java, delegating to
 * {@code Runner}.
 *
 * @since 0.1.0
 */
public class JiuwenCoreAgentHandler implements AgentHandler {
    private static final Logger log = LoggerFactory.getLogger(JiuwenCoreAgentHandler.class);

    private static final AtomicBoolean RUNNER_STARTED = new AtomicBoolean(false);

    /** agent-core-java OutputSchema type name for tool-call interrupts. */
    private static final String INTERACTION_TYPE = "__interaction__";

    private static final String INPUT_QUERY = "query";

    private static final String INPUT_CONVERSATION_ID = "conversation_id";

    private static final String INPUT_MESSAGES = "messages";

    private static final String INPUT_USER_ID = "user_id";

    private static final String INPUT_SPACE_ID = "space_id";

    private static final String INPUT_TENANT_ID = "tenant_id";

    private static final String DEFAULT_AGENT_SESSION_ID = "default_session";

    private static final String SYNTHETIC_AGENT_ID_PREFIX = "service-agentcore:";

    private final Object agent;

    private final MiddlewareAdapterRegistrar middlewareAdapterRegistrar;

    private final ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar;

    /**
     * Creates a handler with the given agent and default middleware/external registrars.
     *
     * @param agent the agent instance or agent-id string
     */
    public JiuwenCoreAgentHandler(Object agent) {
        this(agent, null, ExternalSvcAdapterRegistrar.noop(), null);
    }

    /**
     * Creates a handler with middleware registration support.
     *
     * @param agent the agent instance or agent-id string
     * @param middlewareAdapterRegistrar the middleware adapter registrar
     */
    public JiuwenCoreAgentHandler(Object agent, MiddlewareAdapterRegistrar middlewareAdapterRegistrar) {
        this(agent, middlewareAdapterRegistrar, ExternalSvcAdapterRegistrar.noop(), null);
    }

    /**
     * Creates a handler with external service registration support.
     *
     * @param agent the agent instance or agent-id string
     * @param externalSvcAdapterRegistrar the external service adapter registrar
     */
    public JiuwenCoreAgentHandler(Object agent, ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar) {
        this(agent, null, externalSvcAdapterRegistrar, null);
    }

    /**
     * Creates a handler with middleware and external service registrars.
     *
     * @param agent the agent instance or agent-id string
     * @param middlewareAdapterRegistrar the middleware adapter registrar
     * @param externalSvcAdapterRegistrar the external service adapter registrar
     */
    public JiuwenCoreAgentHandler(Object agent, MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
        ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar) {
        this(agent, middlewareAdapterRegistrar, externalSvcAdapterRegistrar, null);
    }

    /**
     * Creates a handler with middleware and external service support.
     *
     * <p>The {@code memoryProvider} parameter is retained for source compatibility. Runtime exposes
     * the provider bridge as a bean, but Core/Agent configuration owns whether a memory rail is used.
     *
     * @param agent the agent instance or agent-id string
     * @param middlewareAdapterRegistrar the middleware adapter registrar
     * @param externalSvcAdapterRegistrar the external service adapter registrar
     * @param memoryProvider optional core memory provider bridge, not auto-attached by runtime
     */
    public JiuwenCoreAgentHandler(Object agent, MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
        ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar, MemoryProvider memoryProvider) {
        this.agent = agent;
        this.middlewareAdapterRegistrar = middlewareAdapterRegistrar;
        this.externalSvcAdapterRegistrar = externalSvcAdapterRegistrar != null
            ? externalSvcAdapterRegistrar
            : ExternalSvcAdapterRegistrar.noop();
    }

    /**
     * Returns the wrapped agent instance for tests and subclasses.
     *
     * @return the agent delegate
     */
    protected Object getAgent() {
        return agent;
    }

    @Override
    public void start() {
        if (!RUNNER_STARTED.compareAndSet(false, true)) {
            return;
        }
        if (middlewareAdapterRegistrar != null) {
            middlewareAdapterRegistrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig());
        }
        log.info("Starting AgentCore Runner");
        try {
            externalSvcAdapterRegistrar.registerToRunner();
            Runner.start();
        } catch (RuntimeException | Error ex) {
            RUNNER_STARTED.set(false);
            throw ex;
        }
    }

    @Override
    public void stop() {
        if (!RUNNER_STARTED.get()) {
            return;
        }
        log.info("Stopping AgentCore Runner");
        try {
            Runner.stop();
        } catch (Exception ex) {
            log.error("Failed to stop AgentCore Runner", ex);
            throw ex;
        } finally {
            RUNNER_STARTED.set(false);
        }
    }

    static boolean isRunnerStarted() {
        return RUNNER_STARTED.get();
    }

    @Override
    public void clearSession(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        log.info("Releasing AgentCore session for conversation_id={}", conversationId);
        Runner.release(conversationId);
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        String convId = request.getConversationId();
        String query = request.lastUserQuery();
        log.info("JiuwenCoreAgentHandler streamQuery convId={} textLen={} msgCount={}", convId,
            query != null ? query.length() : 0, request.getMessages() != null ? request.getMessages().size() : 0);
        try {
            List<StreamMode> streamModes = List.of(StreamMode.OUTPUT);
            Iterator<Object> source = Runner.runAgentStreaming(agent, buildInputs(request), runnerSession(request),
                null, streamModes);
            while (!observer.isCancelled() && source.hasNext()) {
                if (Thread.currentThread().isInterrupted() || observer.isCancelled()) {
                    break;
                }
                Object raw = source.next();
                Object normalized = normalizeChunk(raw);
                String chunkType = mapToQueryChunkType(normalized);
                observer.onNext(new QueryChunk(chunkType, normalized));
            }
            observer.onComplete();
        } catch (CancellationException ex) {
            observer.onComplete();
        } catch (Exception ex) {
            observer.onNext(new QueryChunk("error", errorEvent(ex)));
            observer.onError(ex);
        }
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        if (supportsInvoke(agent)) {
            Object rawResult = Runner.runAgent(agent, buildInputs(request), runnerSession(request), null);
            return toQueryResponse(rawResult, request.getConversationId());
        }
        return queryViaStreaming(request);
    }

    private QueryResponse queryViaStreaming(ServeRequest request) {
        StringBuilder content = new StringBuilder();
        Object lastPayload = null;
        List<StreamMode> streamModes = List.of(StreamMode.OUTPUT);
        Iterator<Object> source = Runner.runAgentStreaming(agent, buildInputs(request), runnerSession(request), null,
            streamModes);
        while (source.hasNext()) {
            Object payload = normalizeChunk(source.next());
            lastPayload = payload;
            appendContent(payload, content);
        }
        return buildQueryResponse(lastPayload, content, request.getConversationId());
    }

    /**
     * Converts a Core runner result into a {@link QueryResponse}.
     *
     * @param rawResult the raw agent output
     * @param conversationId the conversation identifier
     * @return the normalized query response
     */
    protected QueryResponse toQueryResponse(Object rawResult, String conversationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        if (rawResult instanceof Map<?, ?> rawMap) {
            @SuppressWarnings("unchecked") Map<String, Object> map = (Map<String, Object>) rawMap;
            QueryResponse result1 = getQueryResponse(conversationId, map, result);
            if (result1 != null) {
                return result1;
            }
            Object content = firstNonNull(map.get("output"), map.get("content"), map.get("response")).orElse(null);
            result.put("content", stringify(content));
            return new QueryResponse(result, conversationId);
        }
        if (rawResult instanceof ControllerOutput controllerOutput) {
            return buildQueryResponseFromControllerOutput(controllerOutput, conversationId);
        }
        result.put("content", stringify(rawResult));
        return new QueryResponse(result, conversationId);
    }

    private static QueryResponse getQueryResponse(String conversationId, Map<String, Object> map,
        Map<String, Object> result) {
        if ("interrupt".equals(map.get("result_type")) && map.get("state") instanceof List<?> states) {
            Object lastInterrupt = null;
            for (Object state : states) {
                if (state instanceof OutputSchema outputSchema) {
                    lastInterrupt = normalizeChunk(outputSchema);
                }
            }
            QueryResponse result1 = getQueryResponse(conversationId, lastInterrupt, result);
            if (result1 != null) {
                return result1;
            }
        }
        return null;
    }

    private static QueryResponse getQueryResponse(String conversationId, Object lastInterrupt,
        Map<String, Object> result) {
        if (lastInterrupt instanceof Map<?, ?> interruptMap) {
            @SuppressWarnings("unchecked") Map<String, Object> interruptData = (Map<String, Object>) interruptMap;
            if (INTERACTION_TYPE.equals(interruptData.get("type"))) {
                result.put("_interrupt", interruptData);
                result.put("content", interruptData.getOrDefault("message", ""));
                return new QueryResponse(result, conversationId);
            }
        }
        return null;
    }

    private static QueryResponse buildQueryResponseFromControllerOutput(ControllerOutput controllerOutput,
        String conversationId) {
        StringBuilder content = new StringBuilder();
        Object lastPayload = null;
        Object data = controllerOutput.getData();
        if (data instanceof List<?> items) {
            for (Object item : items) {
                Object payload = normalizeChunk(item);
                lastPayload = payload;
                appendContent(payload, content);
            }
        }
        return buildQueryResponse(lastPayload, content, conversationId);
    }

    private static QueryResponse buildQueryResponse(Object lastPayload, StringBuilder content, String conversationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        if (lastPayload instanceof Map<?, ?> raw && INTERACTION_TYPE.equals(raw.get("type"))) {
            @SuppressWarnings("unchecked") Map<String, Object> interrupt = (Map<String, Object>) raw;
            result.put("_interrupt", interrupt);
            result.put("content", interrupt.getOrDefault("message", ""));
        } else {
            result.put("content", !content.isEmpty() ? content.toString() : stringify(lastPayload));
        }
        return new QueryResponse(result, conversationId);
    }

    private static boolean supportsInvoke(Object agent) {
        if (agent == null || agent instanceof String) {
            // Resolved at runtime from agent-id; use streaming unless the instance exposes
            // invoke.
            return false;
        }
        for (Method method : agent.getClass().getMethods()) {
            if ("invoke".equals(method.getName()) && method.getDeclaringClass() != Object.class) {
                return true;
            }
        }
        return false;
    }

    /**
     * Builds Core runner input map from a serve request.
     *
     * @param request the ingress request
     * @return the runner inputs map
     */
    protected static Map<String, Object> buildInputs(ServeRequest request) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(INPUT_CONVERSATION_ID, request.getConversationId());
        inputs.put(INPUT_MESSAGES, request.getMessages());
        inputs.put(INPUT_USER_ID, request.getUserId());
        inputs.put(INPUT_SPACE_ID, request.getSpaceId());
        if (request.getTenantId() != null) {
            inputs.put(INPUT_TENANT_ID, request.getTenantId());
        }
        String query = request.lastUserQuery();
        if (query != null && !query.isBlank()) {
            inputs.put(INPUT_QUERY, query);
        }
        return inputs;
    }

    /**
     * Resolves the Core runner session object for a serve request.
     *
     * @param request the ingress request
     * @return the session id, conversation id, or {@link AgentSessionApi}
     */
    protected Object runnerSession(ServeRequest request) {
        String conversationId = request.getConversationId();
        String sessionId = conversationId != null && !conversationId.isBlank()
            ? conversationId
            : DEFAULT_AGENT_SESSION_ID;
        Object card = resolveSessionCard();
        return AgentSessionApi.create(sessionId, requestEnvs(request), card, List.of(StreamMode.OUTPUT));
    }

    private Object resolveSessionCard() {
        Object card = readAgentCard(agent);
        if (card != null) {
            return card;
        }
        String agentId = agent instanceof String stringAgentId
            ? stringAgentId
            : SYNTHETIC_AGENT_ID_PREFIX + syntheticAgentClassName();
        String agentName = agent instanceof String stringAgentId ? stringAgentId : syntheticAgentDisplayName();
        return BaseCard.builder()
            .id(agentId)
            .name(agentName)
            .description("Synthetic card for AgentCore session")
            .build();
    }

    private static Map<String, Object> requestEnvs(ServeRequest request) {
        Map<String, Object> envs = new LinkedHashMap<>();
        putIfNotBlank(envs, INPUT_CONVERSATION_ID, request.getConversationId());
        putIfNotBlank(envs, INPUT_USER_ID, request.getUserId());
        putIfNotBlank(envs, INPUT_SPACE_ID, request.getSpaceId());
        putIfNotBlank(envs, INPUT_TENANT_ID, request.getTenantId());
        return envs;
    }

    private static void putIfNotBlank(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String syntheticAgentClassName() {
        return agent != null ? agent.getClass().getName() : "unknown";
    }

    private String syntheticAgentDisplayName() {
        return agent != null ? agent.getClass().getSimpleName() : "unknown";
    }

    private static Object readAgentCard(Object target) {
        if (target == null) {
            return null;
        }
        try {
            Method getter = target.getClass().getMethod("getCard");
            return getter.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            // Fall through to field access.
        }
        try {
            Field field = target.getClass().getDeclaredField("card");
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * Normalizes a Core stream chunk into a service-layer payload map.
     *
     * @param chunk the raw stream chunk
     * @return the normalized payload
     */
    protected static Object normalizeChunk(Object chunk) {
        if (chunk instanceof OutputSchema output) {
            // agent-core-java interrupt: map __interaction__ to structured form
            if (INTERACTION_TYPE.equals(output.getType())) {
                log.info("JiuwenCoreAgentHandler interrupt detected type={}", output.getType());
                return toInterruptData(output);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", output.getType());
            data.put("index", output.getIndex());
            data.put("payload", output.getPayload());
            return data;
        }
        if (chunk instanceof TraceSchema trace) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", trace.getType());
            data.put("payload", trace.getPayload());
            return data;
        }
        if (chunk instanceof Map<?, ?>) {
            return chunk;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "chunk");
        data.put("data", chunk);
        return data;
    }

    /**
     * Maps the internal agent-core OutputSchema type to a standard
     * {@link QueryChunk} type, so downstream code never sees agent-core-internal
     * type strings. Only the interrupt signal needs a distinct top-level type;
     * every other chunk (the final answer included) is a plain
     * {@link QueryChunk#TYPE_CHUNK} — its fine-grained type travels transparently
     * inside the chunk's own {@code {type,index,payload}} envelope.
     *
     * @param normalized
     *            the normalized chunk data
     * @return the QueryChunk type string
     */
    private static String mapToQueryChunkType(Object normalized) {
        if (!(normalized instanceof Map<?, ?> m)) {
            return QueryChunk.TYPE_CHUNK;
        }
        if (INTERACTION_TYPE.equals(m.get("type"))) {
            return QueryChunk.TYPE_INTERRUPT;
        }
        return QueryChunk.TYPE_CHUNK;
    }

    /**
     * Extracts structured interrupt data from an InteractionOutput payload.
     *
     * @param output
     *            the OutputSchema containing the interaction payload
     * @return structured interrupt data map
     */
    private static Map<String, Object> toInterruptData(OutputSchema output) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", INTERACTION_TYPE);
        data.put("index", output.getIndex());
        Object payload = output.getPayload();
        data.put("payload", payload);

        if (payload instanceof InteractionOutput io) {
            extractInteractionData(io, data);
        }
        return data;
    }

    private static void extractInteractionData(InteractionOutput io, Map<String, Object> data) {
        Object value = io.getValue();
        if (value instanceof InterruptRequest req) {
            if (req.getMessage() != null) {
                data.put("message", req.getMessage());
            }
            if (req.getContext() != null) {
                data.put("context", req.getContext());
            }
            if (value instanceof ToolCallInterruptRequest tcr) {
                extractToolCallData(tcr, data);
            }
        }
    }

    private static void extractToolCallData(ToolCallInterruptRequest tcr, Map<String, Object> data) {
        if (tcr.getToolCallId() != null) {
            data.put("toolCallId", tcr.getToolCallId());
        }
        if (tcr.getToolName() != null) {
            data.put("toolName", tcr.getToolName());
        }
    }

    /**
     * Appends textual content from a normalized chunk into the aggregate buffer.
     *
     * @param payload the normalized chunk payload
     * @param content the aggregate content buffer
     */
    protected static void appendContent(Object payload, StringBuilder content) {
        if (!(payload instanceof Map<?, ?> map)) {
            return;
        }
        Object rawPayload = map.get("payload");
        Optional<Object> text = firstNonNull(map.get("content"), map.get("delta"), map.get("output"),
            map.get("response"));
        if (rawPayload instanceof Map<?, ?> payloadMap) {
            Optional<Object> payloadText = firstNonNull(payloadMap.get("content"), payloadMap.get("delta"),
                payloadMap.get("output"), payloadMap.get("response"));
            if (payloadText.isPresent()) {
                text = payloadText;
            }
        }
        if (text.isEmpty()) {
            return;
        }
        Object type = map.get("type");
        String typeText = type == null ? "" : String.valueOf(type);
        if ("answer".equals(typeText) && !content.isEmpty()) {
            return;
        }
        content.append(stringify(text.get()));
    }

    /**
     * Builds a standard error event map for stream observers.
     *
     * @param ex the failure exception
     * @return the error event map
     */
    protected static Map<String, Object> errorEvent(Exception ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "error");
        error.put("error", ex.getMessage() != null ? ex.getMessage() : ex.toString());
        return error;
    }

    private static Optional<Object> firstNonNull(Object... values) {
        return Arrays.stream(values).filter(value -> value != null).findFirst();
    }

    /**
     * Converts a value to a non-null string representation.
     *
     * @param value the value to stringify
     * @return the string form, or empty string when null
     */
    protected static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
