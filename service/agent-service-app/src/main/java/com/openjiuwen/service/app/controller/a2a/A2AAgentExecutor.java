/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.exception.AgentExecutionException;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
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
 * <p>
 * Authoritative admission control: {@link #execute(RequestContext, AgentEmitter)}
 * (SDK entry) and {@code continueTask} (callback continuation entry) both funnel
 * into {@code executeRequest}, which acquires a quota slot up front and releases
 * it in a {@code finally} block on the same thread — exactly-once release is
 * guaranteed by the language structure. Rejection throws an {@link A2AError}
 * before any task state transition, so the SDK's own error path turns it into a
 * FAILED task without manual compensation.
 *
 * @since 0.1.0
 */
public class A2AAgentExecutor implements AgentExecutor {
    private static final Logger log = LoggerFactory.getLogger(A2AAgentExecutor.class);

    private static final String INTERRUPT = "_interrupt";

    /**
     * Error message carried by the A2AError thrown when admission is rejected.
     * Package-visible so the callback continuation can distinguish admission
     * rejection (transient, retryable) from other executor failures.
     */
    static final String ADMISSION_REJECTED_MESSAGE = "Service Unavailable: concurrent task limit reached";

    private static final String GENERIC_EXECUTION_ERROR = "AGENT_EXECUTION_FAILED";

    /**
     * Dead-time bound for waiting on the in-flight queue to drain before closing
     * the stream. The wait returns as soon as the queue actually drains; on timeout
     * the queue stays open so a late interrupted status can still be delivered. The
     * bound is set generously because under
     * a high-latency Redis task store the event-bus processor persists each
     * backed-up streaming event with a blocking round-trip, so a large backlog can
     * take a while to clear.
     */
    private static final long CLOSE_DRAIN_TIMEOUT_MS = 60000L;

    /** Poll interval while waiting for in-flight events to drain. */
    private static final long CLOSE_DRAIN_POLL_MS = 15L;

    private final ServeOrchestrator orchestrator;

    private final A2AProtocolAdapter adapter;

    private final TaskAdmissionGate admissionGate;

    private final ChunkMapper chunkMapper = new ChunkMapper();

    private final ConcurrentMap<String, AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();

    public A2AAgentExecutor(ServeOrchestrator orchestrator, A2AProtocolAdapter adapter) {
        this(orchestrator, adapter, null);
    }

    /**
     * Constructs the agent executor with an admission gate.
     *
     * @param orchestrator the serve orchestrator
     * @param adapter the A2A protocol adapter
     * @param admissionGate the task admission gate; {@code null} disables admission control
     */
    public A2AAgentExecutor(ServeOrchestrator orchestrator, A2AProtocolAdapter adapter,
            TaskAdmissionGate admissionGate) {
        this.orchestrator = orchestrator;
        this.adapter = adapter;
        this.admissionGate = admissionGate;
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
        executeRequest(ctx, msgCtx, req, emitter, task == null);
    }

    void continueTask(RequestContext ctx, ServeRequest request, AgentEmitter emitter) {
        executeRequest(ctx, A2AMessageContext.from(ctx), request, emitter, false);
    }

    private void executeRequest(RequestContext ctx, A2AMessageContext msgCtx, ServeRequest req, AgentEmitter emitter,
            boolean isNewTask) {
        if (admissionGate != null && !admissionGate.tryAcquire()) {
            log.warn("[CONCURRENCY] task_rejected conversationId={} currentActive={} "
                    + "maxConcurrent={} reason=\"limit_reached\"",
                    req.getConversationId(), admissionGate.currentCount(), admissionGate.limit());
            throw new A2AError(A2AErrorCodes.INTERNAL.code(), ADMISSION_REJECTED_MESSAGE, null);
        }
        if (admissionGate != null) {
            log.info("[CONCURRENCY] task_admitted taskId={} conversationId={} currentActive={} maxConcurrent={}",
                    msgCtx.getTaskId(), req.getConversationId(), admissionGate.currentCount(), admissionGate.limit());
        }
        try {
            executeAdmitted(ctx, msgCtx, req, emitter, isNewTask);
        } finally {
            if (admissionGate != null) {
                admissionGate.release();
                log.info("[CONCURRENCY] task_released taskId={} conversationId={} "
                        + "currentActive={} maxConcurrent={}",
                        msgCtx.getTaskId(), req.getConversationId(),
                        admissionGate.currentCount(), admissionGate.limit());
            }
        }
    }

    private void executeAdmitted(RequestContext ctx, A2AMessageContext msgCtx, ServeRequest req, AgentEmitter emitter,
            boolean isNewTask) {
        log.info("A2A execute START taskId={} contextId={} conversationId={} resume={} stream={}", msgCtx.getTaskId(),
                msgCtx.getContextId(), req.getConversationId(), !isNewTask, req.isStream());

        if (isNewTask) {
            emitter.submit();
        }
        emitter.startWork();

        FutureTask<Void> execution = new FutureTask<>(() -> {
            if (req.isStream()) {
                executeStreaming(msgCtx, ctx, req, emitter);
            } else {
                executeQuery(msgCtx, ctx, req, emitter);
            }
            return null;
        });
        execution.run();
        try {
            execution.get();
        } catch (InterruptedException failure) {
            throw new IllegalStateException("A2A execution interrupted", failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Error error) {
                throw error;
            }
            RuntimeException runtimeFailure = cause instanceof RuntimeException runtimeException
                    ? runtimeException
                    : new IllegalStateException("Unexpected A2A execution failure", cause);
            log.error("Agent execution failed for contextId={}", ctx.getContextId(), runtimeFailure);
            failAndDrain(emitter, msgCtx, runtimeFailure);
        }
    }

    private void executeStreaming(A2AMessageContext msgCtx, RequestContext ctx, ServeRequest req,
            AgentEmitter emitter) {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean interrupted = new AtomicBoolean(false);
        AtomicBoolean failed = new AtomicBoolean(false);
        activeCancellations.put(ctx.getContextId(), cancelled);
        try {
            orchestrator.streamQuery(req, new QueryStreamObserver() {
                @Override
                public void onNext(QueryChunk chunk) {
                    handleStreamingChunk(chunk, msgCtx, emitter, interrupted, failed);
                }

                @Override
                public void onComplete() {
                    if (interrupted.get()) {
                        log.info("A2A stream ended after interrupt (COMPLETED suppressed) taskId={}",
                                msgCtx.getTaskId());
                    } else if (failed.get()) {
                        log.info("A2A stream ended after failure (COMPLETED suppressed) taskId={}", msgCtx.getTaskId());
                    } else {
                        log.info("A2A stream complete taskId={}", msgCtx.getTaskId());
                        emitter.complete();
                    }
                }

                @Override
                public void onError(Throwable error) {
                    log.error("A2A agent stream error taskId={} contextId={}", msgCtx.getTaskId(),
                            msgCtx.getContextId(), error);
                    if (failed.compareAndSet(false, true)) {
                        failAndDrain(emitter, msgCtx, error);
                    }
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

    private void handleStreamingChunk(QueryChunk chunk, A2AMessageContext msgCtx, AgentEmitter emitter,
            AtomicBoolean interrupted, AtomicBoolean failed) {
        if (interrupted.get() || failed.get()) {
            return;
        }
        if (QueryChunk.TYPE_ERROR.equals(chunk.getType())) {
            failed.set(true);
            failAndDrain(emitter, msgCtx, streamChunkFailure(chunk));
            return;
        }
        if (QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
            log.info("A2A interrupt detected taskId={} contextId={} message={}", msgCtx.getTaskId(),
                    msgCtx.getContextId(), chunk.getData() instanceof Map<?, ?> map ? map.get("message") : null);
            if (chunk.getData() instanceof Map<?, ?> interruptData) {
                emitter.requiresInput(statusMessage(interruptData));
            } else {
                emitter.requiresInput();
            }
            closeEventQueue(emitter, msgCtx.getTaskId());
            interrupted.set(true);
            return;
        }
        if (QueryChunk.TYPE_REMOTE_AGENT_OUTPUT.equals(chunk.getType())
                && chunk.getData() instanceof TaskArtifactUpdateEvent update) {
            emitter.emitEvent(new TaskArtifactUpdateEvent(msgCtx.getTaskId(), update.artifact(),
                    msgCtx.getContextId(), update.append(), update.lastChunk(), update.metadata()));
            return;
        }
        List<Part<?>> parts = chunkMapper.toParts(chunk);
        if (!parts.isEmpty()) {
            if (chunkMapper.isTerminalResult(chunk)) {
                emitter.addArtifact(parts, null, null, Map.of(A2aPartContent.TERMINAL_RESULT_METADATA, true));
            } else {
                emitter.addArtifact(parts);
            }
        }
    }

    private static RuntimeException streamChunkFailure(QueryChunk chunk) {
        Object data = chunk.getData();
        Optional<String> businessMessage = AgentCoreEnvelopeText.businessText(data);
        String message = businessMessage.orElseGet(() -> {
            if (data instanceof Map<?, ?> map && map.get("error") != null) {
                return String.valueOf(map.get("error"));
            }
            if (data instanceof String text && !text.isBlank()) {
                return text;
            }
            return "Agent streaming execution failed";
        });
        Optional<AgentFailureDescriptor> descriptor = failureDescriptor(data);
        if (descriptor.isPresent()) {
            return new AgentExecutionException(message, descriptor.get(), null);
        }
        return new IllegalStateException(message);
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
                emitter.addArtifact(List.of(new TextPart(String.valueOf(content))), null, null,
                        Map.of(A2aPartContent.TERMINAL_RESULT_METADATA, true));
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
        return Message.builder().role(Message.Role.ROLE_AGENT).parts(List.of(new TextPart(message)))
                .metadata(Map.of(INTERRUPT, interruptData)).build();
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
                closeWhenDrained(queue.get(), taskId, CLOSE_DRAIN_TIMEOUT_MS);
            }
        } catch (ReflectiveOperationException | SecurityException e) {
            log.warn("A2A closeEventQueue failed, falling back to complete() taskId={}", taskId, e);
            emitter.complete();
        }
    }

    static void closeWhenDrained(org.a2aproject.sdk.server.events.EventQueue queue, String taskId, long timeoutMs) {
        if (awaitInFlightDrained(queue, taskId, timeoutMs)) {
            queue.close(false, false);
            log.info("A2A eventQueue closed (INPUT_REQUIRED preserved) taskId={}", taskId);
            return;
        }
        log.warn("A2A eventQueue still has in-flight events after {}ms; leaving it open to preserve INPUT_REQUIRED "
                + "delivery taskId={}", timeoutMs, taskId);
    }

    private static void completeAndDrain(AgentEmitter emitter, String taskId) {
        emitter.complete();
        try {
            Optional<org.a2aproject.sdk.server.events.EventQueue> queue = emitterEventQueue(emitter);
            queue.ifPresent(q -> awaitInFlightDrained(q, taskId, CLOSE_DRAIN_TIMEOUT_MS));
            log.info("A2A eventQueue drained after COMPLETED taskId={}", taskId);
        } catch (ReflectiveOperationException | SecurityException e) {
            log.debug("A2A completeAndDrain: eventQueue unavailable taskId={}", taskId, e);
        }
    }

    private void failAndDrain(AgentEmitter emitter, A2AMessageContext msgCtx, Throwable error) {
        Optional<AgentFailureDescriptor> structuredError = structuredError(error);
        String errorMessage = publicErrorMessage(error, structuredError.isPresent());
        AgentFailureDescriptor descriptor = structuredError.orElseGet(
                () -> new AgentFailureDescriptor(GENERIC_EXECUTION_ERROR, null, false));
        Message message = Message.builder().role(Message.Role.ROLE_AGENT).parts(List.of(new TextPart(errorMessage)))
                .metadata(Map.of(A2aErrorMetadata.KEY, A2aErrorMetadata.encode(descriptor))).build();
        emitter.fail(message);
        String taskId = msgCtx.getTaskId();
        try {
            Optional<org.a2aproject.sdk.server.events.EventQueue> queue = emitterEventQueue(emitter);
            queue.ifPresent(q -> awaitInFlightDrained(q, taskId, CLOSE_DRAIN_TIMEOUT_MS));
            log.info("A2A eventQueue drained after FAILED taskId={}", taskId);
        } catch (ReflectiveOperationException | SecurityException e) {
            log.debug("A2A failAndDrain: eventQueue unavailable taskId={}", taskId, e);
        }
    }

    private static String publicErrorMessage(Throwable error, boolean isStructured) {
        boolean isPreviouslyHandled = error instanceof IllegalArgumentException
                || error instanceof IllegalStateException || error instanceof NullPointerException;
        if ((isStructured || isPreviouslyHandled) && error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage();
        }
        return "Agent execution failed";
    }

    private static Optional<AgentFailureDescriptor> structuredError(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof AgentExecutionException executionException) {
                return Optional.of(executionException.getDescriptor());
            }
            current = current.getCause();
        }
        return Optional.empty();
    }

    private static Optional<AgentFailureDescriptor> failureDescriptor(Object data) {
        if (!(data instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        Object value = map.get("failure");
        if (value instanceof AgentFailureDescriptor descriptor) {
            return Optional.of(descriptor);
        }
        if (!(value instanceof Map<?, ?> descriptor)) {
            return Optional.empty();
        }
        String code = descriptor.get("code") == null ? "" : String.valueOf(descriptor.get("code"));
        if (code.isBlank()) {
            return Optional.empty();
        }
        Integer numericCode = descriptor.get("numericCode") instanceof Number number ? number.intValue() : null;
        boolean isRetryable = descriptor.get("retryable") instanceof Boolean isRetryableValue && isRetryableValue;
        return Optional.of(new AgentFailureDescriptor(code, numericCode, isRetryable));
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
     * drains and only blocks up to the supplied timeout. A timeout or unavailable
     * queue size is reported to the caller so it can avoid closing ahead of an
     * in-flight interrupted status.
     *
     * @param childQueue
     *            the emitter's (child) event queue
     * @param taskId
     *            the A2A task ID for logging
     * @param timeoutMs
     *            maximum time to wait for in-flight events
     * @return {@code true} when the queue drained before the timeout
     */
    private static boolean awaitInFlightDrained(org.a2aproject.sdk.server.events.EventQueue childQueue, String taskId,
            long timeoutMs) {
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
        long deadline = System.currentTimeMillis() + timeoutMs;
        try {
            var sizeMethod = sizeTarget.getClass().getMethod("size");
            sizeMethod.setAccessible(true);
            while (System.currentTimeMillis() < deadline) {
                Object size = sizeMethod.invoke(sizeTarget);
                if (size instanceof Integer i && i <= 0) {
                    return true;
                }
                Thread.sleep(CLOSE_DRAIN_POLL_MS);
            }
            return false;
        } catch (InterruptedException e) {
            log.debug("A2A awaitInFlightDrained interrupted taskId={}", taskId);
        } catch (ReflectiveOperationException | SecurityException e) {
            log.debug("A2A awaitInFlightDrained: size() unavailable taskId={}", taskId);
        }
        return false;
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
