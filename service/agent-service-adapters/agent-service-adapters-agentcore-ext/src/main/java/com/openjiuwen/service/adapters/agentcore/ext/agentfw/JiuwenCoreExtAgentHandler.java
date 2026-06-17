/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.ext.agentfw;

import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptException;
import com.openjiuwen.core.singleagent.interrupt.ToolInterruptionState;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Interrupt-aware extension of {@link com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler}: high-code Agent calling low-code
 * workflows that suspend at interrupt nodes and resume with user input on the next request.
 */
public class JiuwenCoreExtAgentHandler extends JiuwenCoreAgentHandler {

    private static final Logger log = LoggerFactory.getLogger(JiuwenCoreExtAgentHandler.class);

    public JiuwenCoreExtAgentHandler(Object agent) {
        super(agent);
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        try {
            Iterator<Object> source = Runner.runAgentStreaming(
                    getAgent(),
                    buildInterruptAwareInputs(request),
                    request.getConversationId(),
                    null,
                    List.of(StreamMode.OUTPUT));
            while (!observer.isCancelled() && source.hasNext()) {
                if (Thread.currentThread().isInterrupted() || observer.isCancelled()) {
                    break;
                }
                Object normalized = normalizeChunk(source.next());
                if (InterruptEventMapper.isInterruptPayload(normalized)) {
                    log.info("Interrupt node encountered for conversation_id={}", request.getConversationId());
                    observer.onNext(new QueryChunk("interrupt", normalized));
                    observer.onComplete();
                    return;
                }
                observer.onNext(new QueryChunk("chunk", normalized));
            }
            observer.onComplete();
        } catch (ToolInterruptException ex) {
            log.info("Tool interrupt for conversation_id={}, interruptId={}",
                    request.getConversationId(),
                    ex.getRequest() != null ? ex.getRequest().getInterruptId() : null);
            observer.onNext(new QueryChunk("interrupt", InterruptEventMapper.fromException(ex)));
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
        try {
            StringBuilder content = new StringBuilder();
            Object lastPayload = null;
            Iterator<Object> source = Runner.runAgentStreaming(
                    getAgent(),
                    buildInterruptAwareInputs(request),
                    request.getConversationId(),
                    null,
                    List.of(StreamMode.OUTPUT));
            while (source.hasNext()) {
                Object payload = normalizeChunk(source.next());
                lastPayload = payload;
                if (InterruptEventMapper.isInterruptPayload(payload)) {
                    Map<String, Object> result = toInterruptResponse(payload, request.getConversationId());
                    return new QueryResponse(result, request.getConversationId());
                }
                appendContent(payload, content);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("role", "assistant");
            result.put("content", !content.isEmpty() ? content.toString() : stringify(lastPayload));
            return new QueryResponse(result, request.getConversationId());
        } catch (ToolInterruptException ex) {
            Map<String, Object> result = new LinkedHashMap<>(InterruptEventMapper.fromException(ex));
            result.put("role", "assistant");
            result.put("content", stringify(result.get("message")));
            return new QueryResponse(result, request.getConversationId());
        }
    }

    private static Map<String, Object> toInterruptResponse(Object payload, String conversationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (payload instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() != null) {
                    result.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        result.putIfAbsent("type", "interrupt");
        result.putIfAbsent("suspend", true);
        result.put("role", "assistant");
        result.putIfAbsent("content", stringify(result.get("message")));
        return result;
    }

    static Map<String, Object> buildInterruptAwareInputs(ServeRequest request) {
        Map<String, Object> inputs = buildInputs(request);
        Object resumeInput = extractResumeInput(request);
        if (resumeInput != null) {
            inputs.put(ToolInterruptionState.RESUME_USER_INPUT_KEY, resumeInput);
            log.debug("Resume input attached for conversation_id={}", request.getConversationId());
        }
        return inputs;
    }

    static Object extractResumeInput(ServeRequest request) {
        for (Map<String, Object> message : request.getMessages()) {
            if (message == null) {
                continue;
            }
            Object resumeInput = message.get("resume_input");
            if (resumeInput != null) {
                return resumeInput;
            }
            Object metadata = message.get("metadata");
            if (metadata instanceof Map<?, ?> meta) {
                Object nested = meta.get("resume_input");
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }
}
