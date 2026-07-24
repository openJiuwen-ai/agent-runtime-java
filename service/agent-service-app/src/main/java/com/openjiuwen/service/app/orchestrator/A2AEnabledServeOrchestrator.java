/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.StreamCancellationHandle;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * A2A-aware orchestrator with interrupt-resume chain.
 *
 * <p>Delegates {@code a2a_delegate} interrupts (single-agent or parallel batch)
 * to {@link RemoteInvocationBatchCoordinator}, which fans out
 * {@link com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller#callOutcome}
 * invocations and aggregates their outcomes into a single resume or
 * input-required signal. Non-a2a interrupts are forwarded to the client as
 * {@code INPUT_REQUIRED}.
 *
 * @since 0.1.0
 */
public class A2AEnabledServeOrchestrator implements ServeOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(A2AEnabledServeOrchestrator.class);

    /**
     * No-op stream observer used as a sentinel in sync/query mode.
     */
    private static final QueryStreamObserver NOOP_OBSERVER = new QueryStreamObserver() {
        @Override
        public void onNext(QueryChunk chunk) {
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable e) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    private static final String A2A_DELEGATE_KIND = "a2a_delegate";

    private static final String MIXED_INTERRUPT_ERROR = "CORE_INTERRUPT_KIND_MIXED_UNSUPPORTED";

    private static final String CORE_RESUME_IN_FLIGHT = "REMOTE_BATCH_CORE_RESUME_IN_FLIGHT";

    private final AgentHandler agentHandler;

    private final TaskStore taskStore;

    private final ActiveStreamRegistry streamRegistry;

    private final RemoteInvocationBatchCoordinator batchCoordinator;

    /**
     * Constructs the orchestrator with explicit remote invocation limits.
     *
     * @param agentHandler
     *            the local agent handler
     * @param taskStore
     *            the A2A task store for shadow tasks
     * @param remoteAgentCaller
     *            the remote agent caller SPI; consumed by the batch coordinator
     * @param streamRegistry
     *            the active stream registry for cancellation
     * @param agentId
     *            this agent's identity for shadow task key namespacing
     * @param maxConcurrency
     *            global remote invocation concurrency
     * @param maxQueueSize
     *            global pending invocation capacity
     * @param queueTimeoutSeconds
     *            maximum queue wait
     */
    public A2AEnabledServeOrchestrator(AgentHandler agentHandler, TaskStore taskStore,
        RemoteAgentCaller remoteAgentCaller, ActiveStreamRegistry streamRegistry, String agentId,
        int maxConcurrency, int maxQueueSize, long queueTimeoutSeconds) {
        this.agentHandler = agentHandler;
        this.taskStore = taskStore;
        this.streamRegistry = streamRegistry;
        this.batchCoordinator = new RemoteInvocationBatchCoordinator(taskStore, remoteAgentCaller, agentId,
            maxConcurrency, maxQueueSize, queueTimeoutSeconds);
    }

    @Override
    public QueryResponse query(ServeRequest request) {
        log.info("Orchestrator query START conversationId={}", request.getConversationId());
        ServeRequest current = request;
        while (true) {
            QueryResumeResult resumeResult = syncResumePending(current);
            if (resumeResult.response() != null) {
                return resumeResult.response();
            }
            if (resumeResult.request().isEmpty()) {
                return buildInterruptQueryResponse(request.getConversationId());
            }
            current = resumeResult.request().get();

            QueryResponse response;
            try {
                response = agentHandler.query(current);
            } catch (RuntimeException | Error ex) {
                batchCoordinator.abortResume(current);
                throw ex;
            }
            batchCoordinator.completeResume(current);
            Map<String, Object> interruptData = extractInterruptFromResponse(response);
            if (interruptData.isEmpty()) {
                return response;
            }

            Optional<ServeRequest> interruptResult = handleQueryInterrupt(interruptData, current, response);
            if (interruptResult.isEmpty()) {
                return response;
            }
            current = interruptResult.get();
        }
    }

    @Override
    public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
        log.info("Orchestrator streamQuery START conversationId={}", request.getConversationId());
        var handle = streamRegistry.register(request.getConversationId());
        ServeRequest current = request;
        try {
            while (!handle.isCancelled() && !observer.isCancelled()) {
                Optional<ServeRequest> opt = tryResumePending(current, observer);
                if (opt.isEmpty()) {
                    return;
                }
                current = opt.get();

                Optional<QueryChunk> interrupt = runAgentAndCaptureInterrupt(current, observer, handle);
                if (interrupt.isEmpty()) {
                    return;
                }

                Optional<ServeRequest> interruptResult = handleInterrupt(interrupt.get(), current, observer);
                if (interruptResult.isEmpty()) {
                    return;
                }
                current = interruptResult.get();
            }
        } finally {
            batchCoordinator.abortResume(current);
            streamRegistry.unregister(request.getConversationId(), handle);
        }
    }

    /**
     * If a pending remote task exists, resume it.
     *
     * @param current
     *            the current serve request
     * @param observer
     *            the query stream observer
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> tryResumePending(ServeRequest current, QueryStreamObserver observer) {
        Optional<java.util.concurrent.CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> batchResume =
            batchCoordinator.resume(current, observer);
        if (batchResume.isPresent()) {
            try {
                return streamBatchResolution(current, batchResume.get().get(), observer);
            } catch (InterruptedException ex) {
                observer.onError(ex);
                return Optional.empty();
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                observer.onError(cause);
                return Optional.empty();
            }
        }
        return Optional.of(current);
    }

    /**
     * Runs the agent and captures any interrupt chunk.
     *
     * @param current
     *            the current serve request
     * @param observer
     *            the query stream observer
     * @param handle
     *            the stream cancellation handle
     * @return the interrupt chunk, or empty if the stream completed normally
     */
    private Optional<QueryChunk> runAgentAndCaptureInterrupt(ServeRequest current, QueryStreamObserver observer,
        StreamCancellationHandle handle) {
        var interruptHolder = new java.util.concurrent.atomic.AtomicReference<QueryChunk>();
        var coreCompleted = new java.util.concurrent.atomic.AtomicBoolean(false);
        var callbackFailed = new java.util.concurrent.atomic.AtomicBoolean(false);
        try {
            agentHandler.streamQuery(current, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                if (QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())) {
                    interruptHolder.set(chunk);
                    return;
                }
                observer.onNext(chunk);
            }

            @Override
            public void onComplete() {
                if (handle.isCancelled() || observer.isCancelled()) {
                    batchCoordinator.abortResume(current);
                } else {
                    coreCompleted.set(true);
                    batchCoordinator.completeResume(current);
                }
                if (interruptHolder.get() == null) {
                    observer.onComplete();
                }
            }

            @Override
            public void onError(Throwable e) {
                callbackFailed.set(true);
                if (!coreCompleted.get()) {
                    batchCoordinator.abortResume(current);
                }
                log.error("Agent stream error", e);
                observer.onError(e);
            }

            @Override
            public boolean isCancelled() {
                return handle.isCancelled() || observer.isCancelled();
            }
            });
        } catch (RuntimeException | Error ex) {
            if (!coreCompleted.get()) {
                batchCoordinator.abortResume(current);
            }
            throw ex;
        }
        return callbackFailed.get() ? Optional.empty() : Optional.ofNullable(interruptHolder.get());
    }

    /**
     * Routes an interrupt chunk.
     *
     * @param interrupt
     *            the interrupt chunk
     * @param current
     *            the current serve request
     * @param observer
     *            the query stream observer
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private Optional<ServeRequest> handleInterrupt(QueryChunk interrupt, ServeRequest current,
        QueryStreamObserver observer) {
        Map<String, Object> rawInterrupt = interruptMap(interrupt);
        if (isCoordinatorInterrupt(rawInterrupt)) {
            try {
                RemoteInvocationBatchCoordinator.BatchResolution resolution =
                    batchCoordinator.execute(rawInterrupt, current, observer).get();
                return streamBatchResolution(current, resolution, observer);
            } catch (InterruptedException ex) {
                observer.onError(ex);
                return Optional.empty();
            } catch (ExecutionException ex) {
                observer.onError(ex.getCause() != null ? ex.getCause() : ex);
                return Optional.empty();
            }
        }
        if (hasRemoteDelegateItem(rawInterrupt)) {
            observer.onError(new IllegalArgumentException(
                MIXED_INTERRUPT_ERROR + ": mixed A2A and non-A2A interrupts"));
            return Optional.empty();
        }
        if (isRemoteDelegate(rawInterrupt)) {
            observer.onError(new IllegalArgumentException("CORE_INTERRUPT_CORRELATION_MISSING"));
            return Optional.empty();
        }
        log.info("Orchestrator forwarding ask_user interrupt to client convId={}", current.getConversationId());
        observer.onNext(interrupt);
        observer.onComplete();
        return Optional.empty();
    }

    @Override
    public void cancelActive(String conversationId) {
        streamRegistry.cancel(conversationId);
    }

    @Override
    public void resetConversation(String conversationId) {
        cancelActive(conversationId);
        agentHandler.clearSession(conversationId);
        var result = taskStore.list(ListTasksParams.builder().contextId(conversationId).build());
        for (Task t : result.tasks()) {
            taskStore.delete(t.id());
        }
        log.info("Reset {}: {} A2A tasks cleaned", conversationId, result.tasks().size());
    }

    private record QueryResumeResult(Optional<ServeRequest> request, QueryResponse response) {
        static QueryResumeResult continueWith(ServeRequest request) {
            return new QueryResumeResult(Optional.of(request), null);
        }

        static QueryResumeResult stop() {
            return new QueryResumeResult(Optional.empty(), null);
        }

        static QueryResumeResult respond(QueryResponse response) {
            return new QueryResumeResult(Optional.empty(), response);
        }
    }

    /**
     * Sync variant of {@link #tryResumePending} for non-streaming query mode.
     *
     * @param current
     *            the current serve request
     * @return the next {@link ServeRequest} to continue with, or
     *         {@link Optional#empty()} if the loop should stop
     */
    private QueryResumeResult syncResumePending(ServeRequest current) {
        Optional<java.util.concurrent.CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution>> batchResume =
            batchCoordinator.resume(current, NOOP_OBSERVER);
        if (batchResume.isPresent()) {
            try {
                return queryBatchResolution(current, batchResume.get().get(), null);
            } catch (InterruptedException ex) {
                return QueryResumeResult.stop();
            } catch (ExecutionException ex) {
                throw new IllegalStateException("Remote batch resume failed", ex.getCause());
            }
        }
        return QueryResumeResult.continueWith(current);
    }

    /**
     * Handles a2a_delegate interrupt in query mode: forwards once to the remote
     * agent. When {@code resolution.shouldResume()} is {@code true} (tool-call path),
     * the remote's answer is fed back to the parent handler as a tool result.
     * When {@code false} (intent-workflow path), the remote's answer is this
     * layer's final answer and is returned directly without re-invoking the
     * agent.
     *
     * @param interruptData
     *            the interrupt data map
     * @param current
     *            the current serve request
     * @param response
     *            the query response
     * @return a {@link QueryResumeResult} describing the next step
     */
    private Optional<ServeRequest> handleQueryInterrupt(Map<String, Object> interruptData, ServeRequest current,
        QueryResponse response) {
        if (isCoordinatorInterrupt(interruptData)) {
            try {
                QueryResumeResult batchResult = queryBatchResolution(current,
                    batchCoordinator.execute(interruptData, current, NOOP_OBSERVER).get(), response);
                if (batchResult.response() != null) {
                    response.setResult(batchResult.response().getResult());
                    return Optional.empty();
                }
                return batchResult.request();
            } catch (InterruptedException ex) {
                return Optional.empty();
            } catch (ExecutionException ex) {
                throw new IllegalStateException("Remote batch execution failed", ex.getCause());
            }
        }
        if (hasRemoteDelegateItem(interruptData)) {
            throw new IllegalArgumentException(MIXED_INTERRUPT_ERROR + ": mixed A2A and non-A2A interrupts");
        }
        if (isRemoteDelegate(interruptData)) {
            throw new IllegalArgumentException("CORE_INTERRUPT_CORRELATION_MISSING");
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> extractInterruptFromResponse(QueryResponse response) {
        if (response.getResult() instanceof Map<?, ?> m && m.get("_interrupt") instanceof Map<?, ?> interrupt) {
            return (Map<String, Object>) interrupt;
        }
        return new LinkedHashMap<>();
    }

    private Optional<ServeRequest> streamBatchResolution(ServeRequest current,
            RemoteInvocationBatchCoordinator.BatchResolution resolution, QueryStreamObserver observer) {
        if (resolution.isReadyToResume()) {
            if (!resolution.shouldResume()) {
                if (!observer.isCancelled()) {
                    observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, joinRemoteAnswers(resolution)));
                    observer.onComplete();
                }
                return Optional.empty();
            }
            ServeRequest resume = buildBatchResumeRequest(current, resolution);
            if (!batchCoordinator.claimCoreResume(resume, resolution.batchId())) {
                observer.onError(new IllegalStateException(CORE_RESUME_IN_FLIGHT + ": " + resolution.batchId()));
                return Optional.empty();
            }
            return Optional.of(resume);
        }
        observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, resolution.interrupt()));
        observer.onComplete();
        return Optional.empty();
    }

    private QueryResumeResult queryBatchResolution(ServeRequest current,
            RemoteInvocationBatchCoordinator.BatchResolution resolution, QueryResponse response) {
        if (resolution.isReadyToResume()) {
            if (!resolution.shouldResume()) {
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("role", "assistant");
                result.put("content", joinRemoteAnswers(resolution));
                return QueryResumeResult.respond(new QueryResponse(result, current.getConversationId()));
            }
            ServeRequest resume = buildBatchResumeRequest(current, resolution);
            if (!batchCoordinator.claimCoreResume(resume, resolution.batchId())) {
                throw new IllegalStateException(CORE_RESUME_IN_FLIGHT + ": " + resolution.batchId());
            }
            return QueryResumeResult.continueWith(resume);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        if (response != null && response.getResult() instanceof Map<?, ?> existing) {
            existing.forEach((key, value) -> result.put(String.valueOf(key), value));
        } else {
            result.put("role", "assistant");
        }
        result.put("content", resolution.interrupt().getOrDefault("message", "Remote agent requires input"));
        result.put("_interrupt", resolution.interrupt());
        return QueryResumeResult.respond(new QueryResponse(result, current.getConversationId()));
    }

    private static String joinRemoteAnswers(RemoteInvocationBatchCoordinator.BatchResolution resolution) {
        if (resolution.results().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        resolution.results().values().forEach(value -> {
            if (value == null) {
                return;
            }
            String text = value instanceof Map<?, ?> map && map.get("ok") instanceof Boolean isOk && !isOk
                ? "" : String.valueOf(value);
            if (!text.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text);
            }
        });
        return sb.toString();
    }

    private static ServeRequest buildBatchResumeRequest(ServeRequest original,
            RemoteInvocationBatchCoordinator.BatchResolution resolution) {
        ServeRequest resume = new ServeRequest();
        resume.setConversationId(original.getConversationId());
        resume.setStream(original.isStream());
        resume.setMessages(original.getMessages());
        resume.setUserId(original.getUserId());
        resume.setSpaceId(original.getSpaceId());
        resume.setTenantId(original.getTenantId());
        Map<String, Object> metadata = new LinkedHashMap<>(original.getMetadata());
        metadata.remove("runtime.remoteToolInputs");
        metadata.put("runtime.remoteToolResults", new LinkedHashMap<>(resolution.results()));
        metadata.put("runtime.remoteBatchId", resolution.batchId());
        resume.setMetadata(metadata);
        return resume;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> interruptMap(QueryChunk chunk) {
        if (chunk != null && chunk.getData() instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
            return result;
        }
        return Map.of();
    }

    private static boolean isCoordinatorInterrupt(Map<String, Object> interrupt) {
        if (interrupt.get("items") instanceof List<?> items) {
            if (items.isEmpty()) {
                return false;
            }
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> itemMap)
                        || !(itemMap.get("context") instanceof Map<?, ?> context)
                        || !A2A_DELEGATE_KIND.equals(context.get("_interrupt_kind"))) {
                    return false;
                }
            }
            return true;
        }
        return "__interaction__".equals(interrupt.get("type"))
            && isRemoteDelegate(interrupt)
            && interrupt.get("toolCallId") instanceof String toolCallId
            && !toolCallId.isBlank();
    }

    private static boolean isRemoteDelegate(Map<String, Object> interrupt) {
        return interrupt.get("context") instanceof Map<?, ?> context
            && A2A_DELEGATE_KIND.equals(context.get("_interrupt_kind"));
    }

    private static boolean hasRemoteDelegateItem(Map<String, Object> interrupt) {
        if (!(interrupt.get("items") instanceof List<?> items)) {
            return false;
        }
        return items.stream().anyMatch(item -> item instanceof Map<?, ?> itemMap
            && itemMap.get("context") instanceof Map<?, ?> context
            && A2A_DELEGATE_KIND.equals(context.get("_interrupt_kind")));
    }

    private static QueryResponse buildInterruptQueryResponse(String convId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");
        result.put("_interrupt", Map.of("message", "Remote agent requires input"));
        return new QueryResponse(result, convId);
    }
}
