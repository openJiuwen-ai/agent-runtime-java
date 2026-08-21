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
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.locks.LockSupport;

/**
 * Re-enters the existing A2A task event pipeline for a callback continuation.
 *
 * @since 0.1.0
 */
public class A2ATaskContinuation {
    private static final Logger log = LoggerFactory.getLogger(A2ATaskContinuation.class);

    private static final String PARENT_TASK_ID = "runtime.parentTaskId";

    private static final String REMOTE_BATCH_ID = "runtime.remoteBatchId";

    private static final Duration INPUT_REQUIRED_WAIT = Duration.ofSeconds(10);

    private static final long INPUT_REQUIRED_POLL_MS = 20L;

    private final TaskStore taskStore;

    private final QueueManager queueManager;

    private final ObjectProvider<A2AAgentExecutor> agentExecutorProvider;

    private final Executor executor;

    private final Set<String> activeContinuations = ConcurrentHashMap.newKeySet();

    public A2ATaskContinuation(TaskStore taskStore, QueueManager queueManager,
            ObjectProvider<A2AAgentExecutor> agentExecutorProvider, Executor executor) {
        this.taskStore = taskStore;
        this.queueManager = queueManager;
        this.agentExecutorProvider = agentExecutorProvider;
        this.executor = executor;
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
        if (!activeContinuations.add(continuationId)) {
            log.debug("Skipping duplicate A2A callback continuation taskId={} batchId={}", taskId, batchId);
            return;
        }
        try {
            executor.execute(() -> continueTask(taskId, continuationId, request));
        } catch (RejectedExecutionException ex) {
            activeContinuations.remove(continuationId);
            log.warn("A2A callback continuation was rejected taskId={}", taskId, ex);
        }
    }

    private void continueTask(String taskId, String continuationId, ServeRequest request) {
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
                log.warn("A2A callback continuation rejected taskId={} code={} message={}", taskId, error.getCode(),
                        error.getMessage());
            } finally {
                queue.close(false, true);
            }
        } finally {
            activeContinuations.remove(continuationId);
        }
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
