/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A2A SDK {@link AgentExecutor} implementation — the sole bridge between the
 * A2A SDK event pipeline and the internal {@link ServeOrchestrator} →
 * AgentHandler chain.
 *
 * <p>
 * Delegates execution to the orchestrator and owns the A2A protocol projection:
 * interrupt data is stored in task status metadata and restored only for an
 * {@code INPUT_REQUIRED} task resume.
 *
 * @since 0.1.0
 */
public class A2AAgentExecutor implements AgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(A2AAgentExecutor.class);

    private static final String INTERRUPT = "_interrupt";

    /**
     * Dead-time bound for waiting on the in-flight queue to drain before
     * force-closing the stream. The wait returns as soon as the queue actually
     * drains, so this only caps the worst case; it is set generously because under
     * a high-latency Redis task store the event-bus processor persists each
     * backed-up streaming event with a blocking round-trip, so a large backlog can
     * take a while to clear.
     */
    private static final long CLOSE_DRAIN_TIMEOUT_MS = 60000L;

    /** Poll interval while waiting for in-flight events to drain. */
    private static final long CLOSE_DRAIN_POLL_MS = 15L;

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

        Task task = ctx.getTask();
        boolean isInputRequiredResume = task != null && task.status() != null
            && task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED;
        Map<String, Object> metadata = new LinkedHashMap<>(req.getMetadata());
        metadata.remove(INTERRUPT);
        if (isInputRequiredResume) {
            Optional<Map<?, ?>> storedInterrupt = findStoredInterrupt(task);
            if (storedInterrupt.isPresent()) {
                metadata.put(INTERRUPT, storedInterrupt.get());
            }
        }
        req.setMetadata(metadata);
        log.info("A2A execute START taskId={} contextId={} conversationId={} resume={} stream={}", msgCtx.getTaskId(),
            msgCtx.getContextId(), req.getConversationId(), isInputRequiredResume, req.isStream());

        if (task == null) {
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
                            msgCtx.getContextId(), chunk.getData() instanceof Map<?, ?> m ? m.get("message") : null);
                        if (chunk.getData() instanceof Map<?, ?> interruptData) {
                            emitter.requiresInput(statusMessage(interruptData));
                        } else {
                            emitter.requiresInput();
                        }
                        closeEventQueue(emitter, msgCtx.getTaskId());
                        interrupted.set(true);
                        return;
                    }
                    // Transparent passthrough: forward the AgentCore stream chunk verbatim
                    // (the {type,index,payload} envelope), including the final answer, keeping
                    // one uniform stream format. The envelope's own "type" field lets the
                    // delegating caller pick out the answer to feed its LLM without this layer
                    // rewriting the payload — see A2ARemoteAgentClient#handleArtifact.
                    List<Part<?>> parts = chunkMapper.toParts(chunk);
                    if (parts.isEmpty()) {
                        return;
                    }
                    emitter.addArtifact(parts);
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

    private void executeQuery(A2AMessageContext msgCtx, RequestContext ctx, ServeRequest req, AgentEmitter emitter) {
        QueryResponse response = orchestrator.query(req);
        if (response.getResult() instanceof Map<?, ?> result
            && result.get(INTERRUPT) instanceof Map<?, ?> interruptData) {
            log.info("A2A query interrupt detected taskId={} contextId={}", msgCtx.getTaskId(), msgCtx.getContextId());
            emitter.requiresInput(statusMessage(interruptData));
            closeEventQueue(emitter, msgCtx.getTaskId());
        } else if (response.getResult() instanceof Map<?, ?> result) {
            Object content = result.get("content");
            if (content != null) {
                emitter.addArtifact(List.of(new TextPart(String.valueOf(content))));
            }
            completeAndDrain(emitter, msgCtx.getTaskId());
        } else {
            completeAndDrain(emitter, msgCtx.getTaskId());
        }
    }

    private static Message statusMessage(Map<?, ?> interruptData) {
        String message = interruptData.get("message") instanceof String text && !text.isBlank()
            ? text
            : "Input required";
        return Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart(message)))
            .metadata(Map.of(INTERRUPT, interruptData))
            .build();
    }

    private static Optional<Map<?, ?>> findStoredInterrupt(Task task) {
        Optional<Message> statusMessage = Optional.ofNullable(task.status()).map(status -> status.message());
        if (statusMessage.isPresent()) {
            return statusMessage.flatMap(A2AAgentExecutor::interruptFrom);
        }

        List<Message> history = task.history();
        if (history == null) {
            return Optional.empty();
        }
        for (int index = history.size() - 1; index >= 0; index--) {
            Optional<Message> agentMessage = Optional.ofNullable(history.get(index))
                .filter(message -> message.role() == Message.Role.ROLE_AGENT);
            if (agentMessage.isPresent()) {
                return agentMessage.flatMap(A2AAgentExecutor::interruptFrom);
            }
        }
        return Optional.empty();
    }

    private static Optional<Map<?, ?>> interruptFrom(Message message) {
        if (message == null || message.role() != Message.Role.ROLE_AGENT || message.metadata() == null
            || !(message.metadata().get(INTERRUPT) instanceof Map<?, ?> interruptData)) {
            return Optional.empty();
        }
        return Optional.of(interruptData);
    }

    /**
     * Closes the emitter's underlying event queue so the SSE stream terminates
     * without changing the task state (preserving INPUT_REQUIRED for resume).
     *
     * <p>
     * The just-enqueued INPUT_REQUIRED event is delivered to clients asynchronously
     * by the event-bus processor, which <em>persists before distributing</em>. A
     * bare close races that pipeline: with the in-memory store the event is
     * distributed before close takes effect, but a Redis persistence round-trip is
     * slow enough that the consumer sees a closed+empty queue and terminates before
     * the event arrives — dropping INPUT_REQUIRED from the SSE stream. We therefore
     * wait until the per-task queue reports no in-flight events (persisted
     * <em>and</em> distributed to the child consumer queue) before the graceful
     * close, which then lets the consumer drain the delivered event.
     *
     * @param emitter
     *            the agent emitter
     * @param taskId
     *            the A2A task ID for logging
     */
    private static void closeEventQueue(AgentEmitter emitter, String taskId) {
        try {
            Optional<org.a2aproject.sdk.server.events.EventQueue> queue = emitterEventQueue(emitter);
            if (queue.isPresent()) {
                org.a2aproject.sdk.server.events.EventQueue q = queue.get();
                awaitInFlightDrained(q, taskId);
                q.close(false, false);
            }
            log.info("A2A eventQueue closed (INPUT_REQUIRED preserved) taskId={}", taskId);
        } catch (ReflectiveOperationException | SecurityException e) {
            log.warn("A2A closeEventQueue failed, falling back to complete() taskId={}", taskId, e);
            emitter.complete();
        }
    }

    private static void completeAndDrain(AgentEmitter emitter, String taskId) {
        emitter.complete();
        try {
            Optional<org.a2aproject.sdk.server.events.EventQueue> queue = emitterEventQueue(emitter);
            queue.ifPresent(q -> awaitInFlightDrained(q, taskId));
            log.info("A2A eventQueue drained after COMPLETED taskId={}", taskId);
        } catch (ReflectiveOperationException | SecurityException e) {
            log.debug("A2A completeAndDrain: eventQueue unavailable taskId={}", taskId, e);
        }
    }

    private static Optional<org.a2aproject.sdk.server.events.EventQueue> emitterEventQueue(AgentEmitter emitter)
        throws ReflectiveOperationException {
        var f = AgentEmitter.class.getDeclaredField("eventQueue");
        f.setAccessible(true);
        Object queueObj = f.get(emitter);
        if (queueObj instanceof org.a2aproject.sdk.server.events.EventQueue q) {
            return Optional.of(q);
        }
        return Optional.empty();
    }

    /**
     * Waits until the task's parent {@code MainQueue} reports zero in-flight
     * events, i.e. the event-bus processor has persisted and distributed every
     * enqueued event (including the final INPUT_REQUIRED status) to the consumer's
     * child queue. {@code MainQueue.size()} only returns to zero after
     * {@code distributeToChildren()} and the matching semaphore release, so this is
     * the reliable "safe to close" signal. Returns early as soon as the queue
     * drains and only blocks up to {@link #CLOSE_DRAIN_TIMEOUT_MS}; falls back to
     * an immediate close if the topology or {@code size()} cannot be read
     * reflectively.
     *
     * @param childQueue
     *            the emitter's (child) event queue
     * @param taskId
     *            the A2A task ID for logging
     */
    private static void awaitInFlightDrained(org.a2aproject.sdk.server.events.EventQueue childQueue, String taskId) {
        Object sizeTarget = childQueue;
        try {
            var parentField = childQueue.getClass().getDeclaredField("parent");
            parentField.setAccessible(true);
            Object parent = parentField.get(childQueue);
            if (parent != null) {
                sizeTarget = parent;
            }
        } catch (ReflectiveOperationException | SecurityException e) {
            log.debug("A2A awaitInFlightDrained: no parent queue, polling child queue taskId={}", taskId);
        }
        long deadline = System.currentTimeMillis() + CLOSE_DRAIN_TIMEOUT_MS;
        try {
            var sizeMethod = sizeTarget.getClass().getMethod("size");
            sizeMethod.setAccessible(true);
            while (System.currentTimeMillis() < deadline) {
                Object size = sizeMethod.invoke(sizeTarget);
                if (size instanceof Integer i && i <= 0) {
                    return;
                }
                Thread.sleep(CLOSE_DRAIN_POLL_MS);
            }
            log.warn("A2A awaitInFlightDrained timed out after {}ms, closing anyway taskId={}", CLOSE_DRAIN_TIMEOUT_MS,
                taskId);
        } catch (InterruptedException e) {
            log.debug("A2A awaitInFlightDrained interrupted taskId={}", taskId);
        } catch (ReflectiveOperationException | SecurityException e) {
            log.debug("A2A awaitInFlightDrained: size() unavailable, closing immediately taskId={}", taskId);
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
