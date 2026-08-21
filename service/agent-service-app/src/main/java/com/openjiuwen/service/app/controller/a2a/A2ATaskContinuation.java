/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import com.openjiuwen.service.spec.dto.ServeRequest;

import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.EventQueue;
import org.a2aproject.sdk.server.events.QueueManager;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.A2AErrorCodes;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Re-enters the existing A2A task event pipeline for a callback continuation.
 *
 * <p>When the continuation is rejected by the task admission gate (transient
 * overload), it is retried with exponential backoff on a dedicated single-thread
 * scheduler instead of being dropped. The retry budget bounds the total window
 * (1s + 2s + 4s + 8s + 16s); once exhausted the continuation is abandoned and the
 * parent task stays in INPUT_REQUIRED until the client resumes it manually.
 *
 * @since 0.1.0
 */
public class A2ATaskContinuation {
    private static final Logger log = LoggerFactory.getLogger(A2ATaskContinuation.class);

    private static final String PARENT_TASK_ID = "runtime.parentTaskId";

    private static final String REMOTE_BATCH_ID = "runtime.remoteBatchId";

    private static final Duration INPUT_REQUIRED_WAIT = Duration.ofSeconds(10);

    private static final long INPUT_REQUIRED_POLL_MS = 20L;

    /** Maximum number of retries after an admission rejection. */
    private static final int MAX_ADMISSION_RETRIES = 5;

    /** Base delay for the exponential backoff; package-private for tests. */
    static final long DEFAULT_RETRY_BASE_DELAY_MS = 1000L;

    private final TaskStore taskStore;

    private final QueueManager queueManager;

    private final ObjectProvider<A2AAgentExecutor> agentExecutorProvider;

    private final Executor executor;

    private final long retryBaseDelayMs;

    /**
     * Dispatches retry attempts after a delay. Single daemon thread: it only
     * re-submits work to the shared executor and never runs agent logic itself,
     * so retrying cannot consume shared pool capacity.
     */
    private final ScheduledExecutorService retryScheduler;

    /** Continuation markers with the number of admission retries used so far. */
    private final ConcurrentHashMap<String, AtomicInteger> activeContinuations = new ConcurrentHashMap<>();

    /**
     * Creates the continuation adapter with the default retry backoff.
     *
     * @param taskStore the A2A task store
     * @param queueManager the SDK queue manager
     * @param agentExecutorProvider lazy agent executor provider to avoid a bean cycle
     * @param executor the shared execution pool for continuation attempts
     */
    public A2ATaskContinuation(TaskStore taskStore, QueueManager queueManager,
            ObjectProvider<A2AAgentExecutor> agentExecutorProvider, Executor executor) {
        this(taskStore, queueManager, agentExecutorProvider, executor, DEFAULT_RETRY_BASE_DELAY_MS);
    }

    /**
     * Creates the continuation adapter with a configurable retry backoff base delay.
     *
     * @param taskStore the A2A task store
     * @param queueManager the SDK queue manager
     * @param agentExecutorProvider lazy agent executor provider to avoid a bean cycle
     * @param executor the shared execution pool for continuation attempts
     * @param retryBaseDelayMs base delay in milliseconds for the exponential backoff
     */
    A2ATaskContinuation(TaskStore taskStore, QueueManager queueManager,
            ObjectProvider<A2AAgentExecutor> agentExecutorProvider, Executor executor, long retryBaseDelayMs) {
        this.taskStore = taskStore;
        this.queueManager = queueManager;
        this.agentExecutorProvider = agentExecutorProvider;
        this.executor = executor;
        this.retryBaseDelayMs = retryBaseDelayMs;
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "a2a-continuation-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Schedules a single parent task continuation without adding an inbound message.
     *
     * @param request trusted request restored from the remote batch shadow
     */
    public void submit(ServeRequest request) {
        Object value = request == null || request.getMetadata() == null
                ? null
                : request.getMetadata().get(PARENT_TASK_ID);
        if (!(value instanceof String taskId) || taskId.isBlank()) {
            log.warn("Skipping A2A callback continuation without parent task id");
            return;
        }
        String batchId = String.valueOf(request.getMetadata().getOrDefault(REMOTE_BATCH_ID, ""));
        String continuationId = taskId + ":" + batchId;
        if (activeContinuations.putIfAbsent(continuationId, new AtomicInteger()) != null) {
            log.debug("Skipping duplicate A2A callback continuation taskId={} batchId={}", taskId, batchId);
            return;
        }
        dispatch(taskId, batchId, continuationId, request);
    }

    /**
     * Stops the retry scheduler and discards pending retries. Continuations that
     * are already running on the shared executor are not interrupted.
     */
    public void shutdown() {
        retryScheduler.shutdownNow();
    }

    private void dispatch(String taskId, String batchId, String continuationId, ServeRequest request) {
        try {
            executor.execute(() -> continueTask(taskId, batchId, continuationId, request));
        } catch (RejectedExecutionException ex) {
            activeContinuations.remove(continuationId);
            log.warn("A2A callback continuation was rejected taskId={}", taskId, ex);
        }
    }

    private void continueTask(String taskId, String batchId, String continuationId, ServeRequest request) {
        boolean retryPending = false;
        try {
            Optional<Task> task = awaitInputRequired(taskId);
            if (task.isEmpty()) {
                log.warn("A2A callback continuation parent is unavailable or not resumable taskId={}", taskId);
                return;
            }
            A2AAgentExecutor agentExecutor = agentExecutorProvider.getIfAvailable();
            if (agentExecutor == null) {
                log.warn("A2A callback continuation has no agent executor taskId={}", taskId);
                return;
            }
            Task resumableTask = task.get();
            RequestContext context = new RequestContext.Builder().setTaskId(resumableTask.id())
                    .setContextId(resumableTask.contextId()).setTask(resumableTask).build();
            EventQueue queue = queueManager.createOrTap(resumableTask.id());
            try {
                agentExecutor.continueTask(context, request, new AgentEmitter(context, queue));
            } catch (A2AError error) {
                if (isAdmissionRejection(error)) {
                    // scheduleRetry logs its own outcome (retry scheduled or budget exhausted)
                    retryPending = scheduleRetry(taskId, batchId, continuationId, request);
                } else {
                    log.warn("A2A callback continuation rejected taskId={} code={} message={}", taskId, error.getCode(),
                            error.getMessage());
                }
            } finally {
                queue.close(false, true);
            }
        } finally {
            if (!retryPending) {
                activeContinuations.remove(continuationId);
            }
        }
    }

    /**
     * Schedules a delayed re-dispatch for an admission-rejected continuation.
     *
     * <p>The continuation marker is kept while a retry is pending so duplicate
     * submissions stay suppressed. Each retry re-validates the parent task state
     * via {@link #awaitInputRequired}, aborting safely if the task was resumed
     * or finalized by someone else meanwhile.
     *
     * @return {@code true} if a retry was scheduled; {@code false} if the retry
     *         budget was exhausted or the scheduler is shutting down
     */
    private boolean scheduleRetry(String taskId, String batchId, String continuationId, ServeRequest request) {
        AtomicInteger retries = activeContinuations.get(continuationId);
        if (retries == null) {
            return false;
        }
        int attempt = retries.incrementAndGet();
        if (attempt > MAX_ADMISSION_RETRIES) {
            activeContinuations.remove(continuationId);
            log.warn("A2A callback continuation dropped after {} admission retries taskId={} batchId={} "
                    + "conversationId={} — parent task stays in INPUT_REQUIRED until client resume",
                    MAX_ADMISSION_RETRIES, taskId, batchId, request.getConversationId());
            return false;
        }
        long delayMs = retryBaseDelayMs << (attempt - 1);
        log.info("A2A callback continuation deferred by admission control, retry scheduled taskId={} batchId={} "
                + "attempt={} delayMs={}", taskId, batchId, attempt, delayMs);
        try {
            retryScheduler.schedule(() -> dispatch(taskId, batchId, continuationId, request), delayMs,
                    TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException ex) {
            activeContinuations.remove(continuationId);
            log.warn("A2A callback continuation retry scheduling failed during shutdown taskId={}", taskId, ex);
            return false;
        }
    }

    private boolean isAdmissionRejection(A2AError error) {
        Integer code = error.getCode();
        return code != null
                && code == A2AErrorCodes.INTERNAL.code()
                && A2AAgentExecutor.ADMISSION_REJECTED_MESSAGE.equals(error.getMessage());
    }

    private Optional<Task> awaitInputRequired(String taskId) {
        Instant deadline = Instant.now().plus(INPUT_REQUIRED_WAIT);
        while (Instant.now().isBefore(deadline)) {
            Task task = taskStore.get(taskId);
            if (task != null && task.status() != null) {
                if (task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED) {
                    return Optional.of(task);
                }
                if (task.status().state().isFinal()) {
                    return Optional.empty();
                }
            }
            LockSupport.parkNanos(Duration.ofMillis(INPUT_REQUIRED_POLL_MS).toNanos());
            if (Thread.currentThread().isInterrupted()) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }
}
