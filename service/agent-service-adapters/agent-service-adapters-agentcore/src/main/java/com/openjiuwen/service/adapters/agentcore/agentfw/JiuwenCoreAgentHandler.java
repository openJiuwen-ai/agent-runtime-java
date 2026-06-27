/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import com.openjiuwen.core.common.schema.BaseCard;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.session.stream.TraceSchema;
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
 * Default {@link AgentHandler} for OpenJiuwen agent-core-java, delegating to {@code Runner}.
 */
public class JiuwenCoreAgentHandler implements AgentHandler {

    private static final Logger log = LoggerFactory.getLogger(JiuwenCoreAgentHandler.class);
    private static final AtomicBoolean RUNNER_STARTED = new AtomicBoolean(false);

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

    protected Object getAgent() {
        return agent;
    }

    public JiuwenCoreAgentHandler(Object agent) {
        this(agent, null, ExternalSvcAdapterRegistrar.noop());
    }

    public JiuwenCoreAgentHandler(Object agent, MiddlewareAdapterRegistrar middlewareAdapterRegistrar) {
        this(agent, middlewareAdapterRegistrar, ExternalSvcAdapterRegistrar.noop());
    }

    public JiuwenCoreAgentHandler(Object agent, ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar) {
        this(agent, null, externalSvcAdapterRegistrar);
    }

    public JiuwenCoreAgentHandler(Object agent,
                                  MiddlewareAdapterRegistrar middlewareAdapterRegistrar,
                                  ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar) {
        this.agent = agent;
        this.middlewareAdapterRegistrar = middlewareAdapterRegistrar;
        this.externalSvcAdapterRegistrar = externalSvcAdapterRegistrar != null
                ? externalSvcAdapterRegistrar
                : ExternalSvcAdapterRegistrar.noop();
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
        log.info("JiuwenCoreAgentHandler streamQuery convId={} query={} msgCount={}",
                convId, query, request.getMessages() != null ? request.getMessages().size() : 0);
        try {
            List<StreamMode> streamModes = List.of(StreamMode.OUTPUT);
            Iterator<Object> source = Runner.runAgentStreaming(
                    agent,
                    buildInputs(request),
                    runnerSession(request),
                    null,
                    streamModes);
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
        StringBuilder content = new StringBuilder();
        Object lastPayload = null;
        List<StreamMode> streamModes = List.of(StreamMode.OUTPUT);
        Iterator<Object> source = Runner.runAgentStreaming(
                agent,
                buildInputs(request),
                runnerSession(request),
                null,
                streamModes);
        while (source.hasNext()) {
            Object payload = normalizeChunk(source.next());
            lastPayload = payload;
            appendContent(payload, content);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        if (lastPayload instanceof Map<?,?> raw && "__interaction__".equals(raw.get("type"))) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            result.put("_interrupt", m);
            result.put("content", m.getOrDefault("message", ""));
        } else {
            result.put("content", !content.isEmpty() ? content.toString() : stringify(lastPayload));
        }
        return new QueryResponse(result, request.getConversationId());
    }

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

    protected Object runnerSession(ServeRequest request) {
        String conversationId = request.getConversationId();
        if (hasAgentCard(agent)) {
            return conversationId;
        }
        String sessionId = conversationId != null && !conversationId.isBlank()
                ? conversationId
                : DEFAULT_AGENT_SESSION_ID;
        String agentId = agent instanceof String stringAgentId
                ? stringAgentId
                : SYNTHETIC_AGENT_ID_PREFIX + agent.getClass().getName();
        String agentName = agent instanceof String stringAgentId
                ? stringAgentId
                : agent.getClass().getSimpleName();
        BaseCard card = BaseCard.builder()
                .id(agentId)
                .name(agentName)
                .description("Synthetic card for AgentCore session")
                .build();
        return new AgentSessionApi(sessionId, null, card, List.of(StreamMode.OUTPUT));
    }

    private static boolean hasAgentCard(Object target) {
        if (target == null) {
            return false;
        }
        try {
            Method getter = target.getClass().getMethod("getCard");
            return getter.invoke(target) != null;
        } catch (ReflectiveOperationException ignored) {
            // Fall through to field access.
        }
        try {
            Field field = target.getClass().getDeclaredField("card");
            field.setAccessible(true);
            return field.get(target) != null;
        } catch (ReflectiveOperationException ignored) {
            return false;
        }
    }

    protected static Object normalizeChunk(Object chunk) {
        if (chunk instanceof OutputSchema output) {
            // agent-core-java interrupt: map __interaction__ to structured form
            if ("__interaction__".equals(output.getType())) {
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
     * Map the internal agent-core OutputSchema type to a standard
     * {@link QueryChunk} type, so downstream code never sees
     * agent-core-internal type strings.
     */
    private static String mapToQueryChunkType(Object normalized) {
        if (!(normalized instanceof Map<?, ?> m)) return QueryChunk.TYPE_CHUNK;
        Object rawType = m.get("type");
        if ("__interaction__".equals(rawType)) return QueryChunk.TYPE_INTERRUPT;
        if ("answer".equals(rawType)) return QueryChunk.TYPE_ANSWER;
        return QueryChunk.TYPE_CHUNK;
    }

    /**
     * Extract structured interrupt data from an InteractionOutput payload.
     * Uses reflection to avoid a hard compile-scope dependency on agent-core internals.
     */
    private static Map<String, Object> toInterruptData(OutputSchema output) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("type", "__interaction__");
        data.put("index", output.getIndex());
        Object payload = output.getPayload();
        data.put("payload", payload);
        // Extract structured fields from ToolCallInterruptRequest via reflection
        try {
            Object value = payload.getClass().getMethod("getValue").invoke(payload);
            if (value != null) {
                Object message = value.getClass().getMethod("getMessage").invoke(value);
                if (message instanceof String s) data.put("message", s);
                Object toolName = value.getClass().getMethod("getToolName").invoke(value);
                if (toolName instanceof String s) data.put("toolName", s);
                Object toolCallId = value.getClass().getMethod("getToolCallId").invoke(value);
                if (toolCallId instanceof String s) data.put("toolCallId", s);
                Object ctx = value.getClass().getMethod("getContext").invoke(value);
                if (ctx instanceof Map<?,?> m) data.put("context", m);
            }
        } catch (Exception ignored) {
            // Non-InteractionOutput payload or missing getters; raw data is sufficient
        }
        return data;
    }

    protected static void appendContent(Object payload, StringBuilder content) {
        if (!(payload instanceof Map<?, ?> map)) {
            return;
        }
        Object type = map.get("type");
        Object rawPayload = map.get("payload");
        Optional<Object> text = firstNonNull(
                map.get("content"),
                map.get("delta"),
                map.get("output"),
                map.get("response"));
        if (rawPayload instanceof Map<?, ?> payloadMap) {
            Optional<Object> payloadText = firstNonNull(
                    payloadMap.get("content"),
                    payloadMap.get("delta"),
                    payloadMap.get("output"),
                    payloadMap.get("response"));
            if (payloadText.isPresent()) {
                text = payloadText;
            }
        }
        if (text.isEmpty()) {
            return;
        }
        String typeText = type == null ? "" : String.valueOf(type);
        if ("answer".equals(typeText) && !content.isEmpty()) {
            return;
        }
        content.append(stringify(text.get()));
    }

    protected static Map<String, Object> errorEvent(Exception ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "error");
        error.put("error", ex.getMessage() != null ? ex.getMessage() : ex.toString());
        return error;
    }

    private static Optional<Object> firstNonNull(Object... values) {
        return Arrays.stream(values)
                .filter(value -> value != null)
                .findFirst();
    }

    protected static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
