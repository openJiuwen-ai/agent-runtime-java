/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A2A SDK {@link AgentExecutor} implementation — the sole bridge between the A2A SDK event pipeline and the internal
 * {@link ServeOrchestrator} → AgentHandler chain.
 *
 * <p>
 * Delegates stream/chunk handling to the orchestrator. Interrupt detection and resume logic belong to the orchestrator
 * layer, not here.
 *
 * @since 0.1.0
 */
public class A2AAgentExecutor implements AgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(A2AAgentExecutor.class);

    private final ServeOrchestrator orchestrator;
    private final A2AProtocolAdapter adapter;
    private final ChunkMapper chunkMapper = new ChunkMapper();
    private final ConcurrentMap<String, AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();

    public A2AAgentExecutor(ServeOrchestrator orchestrator, A2AProtocolAdapter adapter) {
        this.orchestrator = orchestrator;
        this.adapter = adapter;
    }

    @Override
    public void execute(RequestContext ctx, AgentEmitter emitter) {
        A2AMessageContext msgCtx = A2AMessageContext.from(ctx);
        ServeRequest req = adapter.toServeRequest(msgCtx);
        if (ctx.getCallContext().getState().get("_a2a_stream") instanceof Boolean isStream) {
            req.setStream(isStream);
        }

        boolean isResume = ctx.getTask() != null;
        log.info("A2A execute START taskId={} contextId={} conversationId={} resume={} stream={}", msgCtx.getTaskId(),
                msgCtx.getContextId(), req.getConversationId(), isResume, req.isStream());

        if (!isResume) {
            emitter.submit();
        }
        emitter.startWork();

        try {
            if (req.isStream()) {
                executeStreaming(msgCtx, ctx, req, emitter);
            } else {
                executeQuery(msgCtx, ctx, req, emitter);
            }
        } catch (IllegalStateException | NullPointerException ex) {
            log.error("Agent execution failed for contextId={}", ctx.getContextId(), ex);
            emitter.fail();
        }
    }

    private void executeStreaming(A2AMessageContext msgCtx, RequestContext ctx, ServeRequest req,
            AgentEmitter emitter) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        activeCancellations.put(ctx.getContextId(), cancelled);
        try {
            orchestrator.streamQuery(req, new QueryStreamObserver() {
                @Override
                public void onNext(QueryChunk chunk) {
                    if (QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
                        log.info("A2A interrupt detected taskId={} contextId={} message={}", msgCtx.getTaskId(),
                                msgCtx.getContextId(),
                                chunk.getData() instanceof Map<?, ?> m ? m.get("message") : null);
                        Message statusMsg = toStatusMessage(chunk).orElse(null);
                        emitter.requiresInput(statusMsg);
                        closeEventQueue(emitter, msgCtx.getTaskId());
                        interrupted.set(true);
                        return;
                    }
                    List<Part<?>> parts = chunkMapper.toParts(chunk);
                    if (parts.isEmpty()) {
                        return;
                    }
                    Map<String, Object> metadata = QueryChunk.TYPE_ANSWER.equals(chunk.getType())
                            ? Map.of("answer", true)
                            : null;
                    emitter.addArtifact(parts, null, null, metadata);
                }

                @Override
                public void onComplete() {
                    if (interrupted.get()) {
                        log.info("A2A stream ended after interrupt (COMPLETED suppressed) taskId={}",
                                msgCtx.getTaskId());
                    } else {
                        log.info("A2A stream complete taskId={}", msgCtx.getTaskId());
                        emitter.complete();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    log.error("A2A agent stream error taskId={} contextId={}", msgCtx.getTaskId(),
                            msgCtx.getContextId(), error);
                    emitter.fail();
                }

                @Override
                public boolean isCancelled() {
                    return cancelled.get();
                }
            });
        } finally {
            activeCancellations.remove(ctx.getContextId());
        }
    }

    private void executeQuery(A2AMessageContext msgCtx, RequestContext ctx, ServeRequest req,
            AgentEmitter emitter) {
        QueryResponse response = orchestrator.query(req);
        if (response.getResult() instanceof Map<?, ?> result
                && result.get("_interrupt") instanceof Map<?, ?> interruptData) {
            log.info("A2A query interrupt detected taskId={} contextId={}", msgCtx.getTaskId(),
                    msgCtx.getContextId());
            Message statusMsg = toStatusMessageFromMap(interruptData).orElse(null);
            emitter.requiresInput(statusMsg);
            closeEventQueue(emitter, msgCtx.getTaskId());
        } else if (response.getResult() instanceof Map<?, ?> result) {
            Object content = result.get("content");
            if (content != null) {
                emitter.addArtifact(List.of(new TextPart(String.valueOf(content))));
            }
            emitter.complete();
        } else {
            emitter.complete();
        }
    }

    private static Optional<Message> toStatusMessage(QueryChunk chunk) {
        if (chunk.getData() instanceof Map<?, ?> m && m.get("message") instanceof String s && !s.isBlank()) {
            return Optional.of(Message.builder().role(Message.Role.ROLE_AGENT)
                    .parts(List.of(new TextPart(s))).build());
        }
        return Optional.empty();
    }

    private static Optional<Message> toStatusMessageFromMap(Map<?, ?> interruptData) {
        if (interruptData.get("message") instanceof String s && !s.isBlank()) {
            return Optional.of(Message.builder().role(Message.Role.ROLE_AGENT)
                    .parts(List.of(new TextPart(s))).build());
        }
        return Optional.empty();
    }

    /**
     * Closes the emitter's underlying event queue so the SSE stream terminates without changing the task state
     * (preserving INPUT_REQUIRED for resume).
     *
     * @param emitter the agent emitter
     * @param taskId the A2A task ID for logging
     */
    private static void closeEventQueue(AgentEmitter emitter, String taskId) {
        try {
            var f = emitter.getClass().getDeclaredField("eventQueue");
            f.setAccessible(true);
            Object queueObj = f.get(emitter);
            if (queueObj instanceof org.a2aproject.sdk.server.events.EventQueue q) {
                q.close(false, false);
            }
            log.info("A2A eventQueue closed (INPUT_REQUIRED preserved) taskId={}", taskId);
        } catch (ReflectiveOperationException | SecurityException e) {
            log.warn("A2A closeEventQueue failed, falling back to complete() taskId={}", taskId, e);
            emitter.complete();
        }
    }

    @Override
    public void cancel(RequestContext ctx, AgentEmitter emitter) {
        String contextId = ctx.getContextId();
        AtomicBoolean cancelled = activeCancellations.get(contextId);
        if (cancelled != null) {
            cancelled.set(true);
        }
        orchestrator.cancelActive(contextId);
        emitter.cancel();
    }
}
