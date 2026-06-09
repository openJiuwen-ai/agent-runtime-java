package com.openjiuwen.service.adapters.agentfw;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.session.stream.TraceSchema;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Default {@link AgentHandler} delegating to AgentCore {@code Runner}.
 */
public class CoreAgentHandler implements AgentHandler {

    private static final String INPUT_QUERY = "query";
    private static final String INPUT_CONVERSATION_ID = "conversation_id";
    private static final String INPUT_MESSAGES = "messages";
    private static final String INPUT_USER_ID = "user_id";
    private static final String INPUT_SPACE_ID = "space_id";
    private static final String INPUT_TENANT_ID = "tenant_id";

    private final Object agent;

    public CoreAgentHandler(Object agent) {
        this.agent = agent;
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        try {
            Iterator<Object> source = Runner.runAgentStreaming(
                    agent,
                    buildInputs(request),
                    request.getConversationId(),
                    null,
                    List.of(StreamMode.OUTPUT));
            while (!observer.isCancelled() && source.hasNext()) {
                if (Thread.currentThread().isInterrupted() || observer.isCancelled()) {
                    break;
                }
                observer.onNext(new QueryChunk("chunk", normalizeChunk(source.next())));
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
        Iterator<Object> source = Runner.runAgentStreaming(
                agent,
                buildInputs(request),
                request.getConversationId(),
                null,
                List.of(StreamMode.OUTPUT));
        while (source.hasNext()) {
            Object payload = normalizeChunk(source.next());
            lastPayload = payload;
            appendContent(payload, content);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("content", !content.isEmpty() ? content.toString() : stringify(lastPayload));
        return new QueryResponse(result, request.getConversationId());
    }

    private static Map<String, Object> buildInputs(ServeRequest request) {
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

    private static Object normalizeChunk(Object chunk) {
        if (chunk instanceof OutputSchema output) {
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

    private static void appendContent(Object payload, StringBuilder content) {
        if (!(payload instanceof Map<?, ?> map)) {
            return;
        }
        Object type = map.get("type");
        Object rawPayload = map.get("payload");
        Object text = firstNonNull(map.get("content"), map.get("delta"), map.get("output"), map.get("response"));
        if (rawPayload instanceof Map<?, ?> payloadMap) {
            text = firstNonNull(payloadMap.get("content"), payloadMap.get("delta"),
                    payloadMap.get("output"), payloadMap.get("response"), text);
        }
        if (text == null) {
            return;
        }
        String typeText = type == null ? "" : String.valueOf(type);
        if ("answer".equals(typeText) && !content.isEmpty()) {
            return;
        }
        content.append(stringify(text));
    }

    private static Map<String, Object> errorEvent(Exception ex) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("type", "error");
        error.put("error", ex.getMessage() != null ? ex.getMessage() : ex.toString());
        return error;
    }

    private static Object firstNonNull(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String stringify(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
