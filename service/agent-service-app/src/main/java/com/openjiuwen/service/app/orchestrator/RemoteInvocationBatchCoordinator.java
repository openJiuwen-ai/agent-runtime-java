/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.controller.a2a.A2aPartContent;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runtime-owned fan-out/fan-in coordinator for remote-agent tool-call batches.
 *
 * <p>The dispatcher is shared by all batches in this coordinator instance. It
 * starts remote calls only when a concurrency slot is available and never owns
 * a thread pool; completion callbacks release slots and advance the FIFO.
 *
 * @since 0.1.0
 */
final class RemoteInvocationBatchCoordinator {
    private static final Logger log = LoggerFactory.getLogger(RemoteInvocationBatchCoordinator.class);

    private static final String SHADOW_PREFIX = "shadow:";

    private static final String REMOTE_BATCH = "_remote_batch";

    private static final String PARENT_TASK_ID = "runtime.parentTaskId";

    private static final String REMOTE_TOOL_INPUTS = "runtime.remoteToolInputs";

    private static final String REMOTE_BATCH_ID = "runtime.remoteBatchId";

    private static final Set<String> RESERVED_METADATA = Set.of("_interrupt", PARENT_TASK_ID, REMOTE_TOOL_INPUTS,
            REMOTE_BATCH_ID, "runtime.remoteToolResults");

    private static final int MAX_EARLY_CALLBACKS = 256;

    private static final QueryStreamObserver NOOP_OBSERVER = new QueryStreamObserver() {
        @Override
        public void onNext(QueryChunk chunk) {
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable error) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    private final TaskStore taskStore;

    private final RemoteAgentCaller client;

    private final String agentId;

    private final int maxConcurrency;

    private final int maxQueueSize;

    private final Duration queueTimeout;

    private final Object lock = new Object();

    private final Deque<PendingInvocation> queue = new ArrayDeque<>();

    private final Map<String, Batch> activeByParent = new LinkedHashMap<>();

    private final Map<String, String> coreResumeClaims = new LinkedHashMap<>();

    private final Map<String, Task> earlyCallbacksByRemoteTaskId = new LinkedHashMap<>();

    private int activeCount;

    /**
     * Creates a coordinator with a global bounded dispatcher.
     *
     * @param taskStore existing A2A task store
     * @param client remote agent caller SPI
     * @param agentId local agent identity used in shadow keys
     * @param maxConcurrency maximum running remote members
     * @param maxQueueSize maximum queued remote members
     * @param queueTimeoutSeconds maximum queue wait in seconds
     */
    RemoteInvocationBatchCoordinator(TaskStore taskStore, RemoteAgentCaller client, String agentId, int maxConcurrency,
            int maxQueueSize, long queueTimeoutSeconds) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be greater than zero");
        }
        if (maxQueueSize < 0) {
            throw new IllegalArgumentException("maxQueueSize must not be negative");
        }
        if (queueTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("queueTimeoutSeconds must be greater than zero");
        }
        this.taskStore = taskStore;
        this.client = client;
        this.agentId = agentId == null || agentId.isBlank() ? "agent" : agentId;
        this.maxConcurrency = maxConcurrency;
        this.maxQueueSize = maxQueueSize;
        this.queueTimeout = Duration.ofSeconds(queueTimeoutSeconds);
    }

    /**
     * Executes a normalized Core interrupt batch.
     *
     * @param interrupt normalized single or multi-member interrupt
     * @param request current parent request
     * @param observer parent observer for remote business output
     * @return asynchronous batch resolution
     */
    CompletableFuture<BatchResolution> execute(Map<String, Object> interrupt, ServeRequest request,
            QueryStreamObserver observer) {
        String parentTaskId = parentTaskId(request);
        Batch batch;
        try {
            batch = parseBatch(interrupt, request, parentTaskId, new SerialObserver(observer));
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.failedFuture(ex);
        }
        Optional<CompletableFuture<BatchResolution>> conflict = registerBatch(batch);
        if (conflict.isPresent()) {
            return conflict.get();
        }
        for (Member member : batch.members) {
            submit(new PendingInvocation(batch, member));
        }
        return batch.completion;
    }

    private Optional<CompletableFuture<BatchResolution>> registerBatch(Batch batch) {
        String parentTaskId = batch.parentTaskId;
        synchronized (lock) {
            Batch active = activeByParent.get(parentTaskId);
            if (active != null) {
                return Optional.of(CompletableFuture
                        .failedFuture(new IllegalStateException("REMOTE_BATCH_ALREADY_ACTIVE: " + parentTaskId)));
            }
        }
        Task persisted = taskStore.get(shadowTaskId(parentTaskId));
        if (isBatchShadow(persisted)) {
            return Optional.of(CompletableFuture
                    .failedFuture(new IllegalStateException("REMOTE_BATCH_ALREADY_PENDING: " + parentTaskId)));
        }
        synchronized (lock) {
            Batch active = activeByParent.get(parentTaskId);
            if (active != null) {
                return Optional.of(CompletableFuture
                        .failedFuture(new IllegalStateException("REMOTE_BATCH_ALREADY_ACTIVE: " + parentTaskId)));
            }
            activeByParent.put(parentTaskId, batch);
        }
        return Optional.empty();
    }

    /**
     * Resumes selected members of an existing remote batch shadow.
     *
     * @param request current parent request containing trusted targeted inputs
     * @param observer parent observer for remote business output
     * @return empty when no remote batch shadow exists
     */
    Optional<CompletableFuture<BatchResolution>> resume(ServeRequest request, QueryStreamObserver observer) {
        if (request.getMetadata().get("runtime.remoteToolResults") instanceof Map<?, ?>
                && !stringValue(request.getMetadata().get(REMOTE_BATCH_ID)).isBlank()) {
            return Optional.empty();
        }
        String parentTaskId = parentTaskId(request);
        Map<String, String> targetedInputs = targetedInputs(request);
        Task shadow = taskStore.get(shadowTaskId(parentTaskId));
        if (shadow == null || shadow.metadata() == null
                || !(shadow.metadata().get(REMOTE_BATCH) instanceof Map<?, ?> rawBatch)) {
            if (!targetedInputs.isEmpty()) {
                return Optional.of(
                        CompletableFuture.failedFuture(new IllegalArgumentException("REMOTE_BATCH_PARENT_MISMATCH")));
            }
            return Optional.empty();
        }
        String snapshotParentTaskId = stringValue(rawBatch.get("parentTaskId"));
        if (!snapshotParentTaskId.isBlank() && !snapshotParentTaskId.equals(parentTaskId)) {
            return Optional
                    .of(CompletableFuture.failedFuture(new IllegalArgumentException("REMOTE_BATCH_PARENT_MISMATCH")));
        }
        Batch batch = restoreBatch(rawBatch, request, parentTaskId, new SerialObserver(observer));
        if ("READY_TO_RESUME".equals(stringValue(rawBatch.get("state")))) {
            return resumeReadyBatch(batch, targetedInputs);
        }
        return resumeWaitingBatch(batch, targetedInputs, parentTaskId, request.lastUserQuery());
    }

    boolean recoverCallback(Task task) {
        if (task == null || task.id() == null || task.id().isBlank()) {
            return false;
        }
        var shadows = taskStore.list(ListTasksParams.builder().build()).tasks();
        for (Task shadow : shadows) {
            if (!isBatchShadow(shadow)) {
                continue;
            }
            Map<?, ?> rawBatch = (Map<?, ?>) shadow.metadata().get(REMOTE_BATCH);
            if (recoverShadow(shadow, rawBatch, task)) {
                return true;
            }
        }
        rememberEarlyCallback(task);
        return false;
    }

    private void rememberEarlyCallback(Task task) {
        synchronized (lock) {
            earlyCallbacksByRemoteTaskId.put(task.id(), task);
            while (earlyCallbacksByRemoteTaskId.size() > MAX_EARLY_CALLBACKS) {
                String eldest = earlyCallbacksByRemoteTaskId.keySet().iterator().next();
                earlyCallbacksByRemoteTaskId.remove(eldest);
            }
        }
    }

    private boolean recoverShadow(Task shadow, Map<?, ?> rawBatch, Task task) {
        String parentTaskId = stringValue(rawBatch.get("parentTaskId"));
        ServeRequest request = new ServeRequest();
        request.setConversationId(shadow.contextId());
        request.setMetadata(parentTaskId.isBlank() ? Map.of() : Map.of(PARENT_TASK_ID, parentTaskId));
        Batch batch = restoreBatch(rawBatch, request, parentTaskId, new SerialObserver(NOOP_OBSERVER));
        Optional<Member> matched = batch.members.stream().filter(member -> task.id().equals(member.remoteTaskId))
                .findFirst();
        if (matched.isEmpty()) {
            return false;
        }
        Member member = matched.get();
        if (member.state == MemberState.COMPLETED) {
            return true;
        }
        applyOutcome(member, callbackOutcome(task), null);
        saveShadow(batch, shadowState(batch));
        return true;
    }

    private Optional<CompletableFuture<BatchResolution>> resumeReadyBatch(Batch batch,
            Map<String, String> targetedInputs) {
        Set<String> memberIds = new LinkedHashSet<>();
        batch.members.forEach(member -> memberIds.add(member.toolCallId));
        for (String toolCallId : targetedInputs.keySet()) {
            if (!memberIds.contains(toolCallId)) {
                return Optional.of(CompletableFuture
                        .failedFuture(new IllegalArgumentException("REMOTE_TOOL_INPUT_TARGET_UNKNOWN: " + toolCallId)));
            }
        }
        return Optional.of(CompletableFuture.completedFuture(snapshotResolution(batch)));
    }

    private Optional<CompletableFuture<BatchResolution>> resumeWaitingBatch(Batch batch,
            Map<String, String> targetedInputs, String parentTaskId, String lastUserQuery) {
        List<Member> pending = batch.members.stream().filter(member -> member.state == MemberState.INPUT_REQUIRED)
                .toList();
        Map<String, String> effectiveInputs;
        if (targetedInputs.isEmpty()) {
            if (pending.size() != 1) {
                return Optional.of(CompletableFuture
                        .failedFuture(new IllegalArgumentException("REMOTE_TOOL_INPUT_TARGET_REQUIRED")));
            }
            effectiveInputs = Map.of(pending.get(0).toolCallId, lastUserQuery);
        } else {
            effectiveInputs = targetedInputs;
        }
        Map<String, Member> membersById = new LinkedHashMap<>();
        batch.members.forEach(member -> membersById.put(member.toolCallId, member));
        for (String toolCallId : effectiveInputs.keySet()) {
            Member member = membersById.get(toolCallId);
            if (member == null) {
                return Optional.of(CompletableFuture
                        .failedFuture(new IllegalArgumentException("REMOTE_TOOL_INPUT_TARGET_UNKNOWN: " + toolCallId)));
            }
            if (member.state != MemberState.INPUT_REQUIRED) {
                return Optional.of(CompletableFuture
                        .failedFuture(new IllegalArgumentException("REMOTE_TOOL_INPUT_STATE_CONFLICT: " + toolCallId)));
            }
        }
        List<Member> selected = new ArrayList<>();
        effectiveInputs.forEach((toolCallId, input) -> {
            Member member = membersById.get(toolCallId);
            member.message = input;
            member.resultCategory = null;
            member.inputPrompt = null;
            member.state = MemberState.QUEUED;
            member.queuedAt = Instant.now();
            selected.add(member);
        });
        synchronized (lock) {
            if (activeByParent.containsKey(parentTaskId)) {
                return Optional.of(CompletableFuture
                        .failedFuture(new IllegalStateException("REMOTE_BATCH_ALREADY_ACTIVE: " + parentTaskId)));
            }
            activeByParent.put(parentTaskId, batch);
        }
        selected.forEach(member -> submit(new PendingInvocation(batch, member)));
        return Optional.of(batch.completion);
    }

    private static boolean isBatchShadow(Task task) {
        return task != null && task.status() != null && task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED
                && task.metadata() != null && task.metadata().get(REMOTE_BATCH) instanceof Map<?, ?>;
    }

    private void submit(PendingInvocation invocation) {
        boolean shouldStart = false;
        boolean isQueued = false;
        boolean isOverloaded = false;
        synchronized (lock) {
            if (invocation.batch.isResolved) {
                return;
            }
            if (activeCount < maxConcurrency) {
                activeCount++;
                invocation.member.state = MemberState.RUNNING;
                invocation.member.startedAt = Instant.now();
                shouldStart = true;
            } else if (queue.size() < maxQueueSize) {
                invocation.member.state = MemberState.QUEUED;
                invocation.member.queuedAt = Instant.now();
                queue.addLast(invocation);
                isQueued = true;
            } else {
                invocation.member.fail(MemberState.FAILED, "REMOTE_OVERLOADED", "Remote invocation queue is full");
                isOverloaded = true;
            }
        }
        logMemberState(invocation.batch, invocation.member);
        if (shouldStart) {
            start(invocation);
            return;
        }
        if (isQueued) {
            CompletableFuture.delayedExecutor(queueTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> expireQueued(invocation));
            return;
        }
        if (isOverloaded) {
            finishBatchIfSettled(invocation.batch);
        }
    }

    private void expireQueued(PendingInvocation invocation) {
        boolean isExpired = false;
        synchronized (lock) {
            if (!invocation.batch.isResolved && invocation.member.state == MemberState.QUEUED
                    && queue.remove(invocation)) {
                invocation.member.fail(MemberState.FAILED, "REMOTE_OVERLOADED",
                        "Remote invocation queue wait timed out");
                isExpired = true;
            }
        }
        if (!isExpired) {
            return;
        }
        logMemberState(invocation.batch, invocation.member);
        finishBatchIfSettled(invocation.batch);
    }

    private void start(PendingInvocation invocation) {
        Member member = invocation.member;
        Batch batch = invocation.batch;
        boolean isStartAllowed;
        synchronized (lock) {
            isStartAllowed = !batch.isResolved && member.state == MemberState.RUNNING;
            if (!isStartAllowed) {
                activeCount = Math.max(0, activeCount - 1);
            }
        }
        if (!isStartAllowed) {
            startNextQueuedAfterReleasedSlot();
            return;
        }
        Map<String, Object> metadata = outboundMetadata(batch.request.getMetadata());
        String userId = batch.request.getUserId();
        if (userId != null && !userId.isBlank() && !metadata.containsKey("userId")) {
            metadata.put("userId", userId);
        }
        RemoteCall call = new RemoteCall(member.agentName, member.message, remoteContextId(batch, member),
                optionalNonBlank(member.remoteTaskId).orElse(null), metadata, batch.request.lastUserMessageMetadata(),
                batch.request.isStream());
        QueryStreamObserver outputObserver = memberOutputObserver(batch, member);
        CompletableFuture<RemoteCallOutcome> future;
        try {
            future = client.callOutcome(call, outputObserver, remoteTaskId -> {
                if (remoteTaskId != null && !remoteTaskId.isBlank()) {
                    synchronized (lock) {
                        if (!batch.isResolved && member.state == MemberState.RUNNING) {
                            member.remoteTaskId = remoteTaskId;
                        }
                    }
                }
            });
        } catch (RuntimeException ex) {
            finishInvocation(invocation, null, ex);
            return;
        }
        future.whenComplete((outcome, error) -> finishInvocation(invocation, outcome, error));
    }

    private void finishInvocation(PendingInvocation invocation, RemoteCallOutcome outcome, Throwable error) {
        Batch batch = invocation.batch;
        Member member = invocation.member;
        List<PendingInvocation> expired = new ArrayList<>();
        PendingInvocation next = null;
        boolean shouldApplyOutcome;
        synchronized (lock) {
            shouldApplyOutcome = !batch.isResolved;
            if (shouldApplyOutcome) {
                applyOutcome(member, outcome, error);
            }
            activeCount = Math.max(0, activeCount - 1);
            while (!queue.isEmpty() && next == null) {
                PendingInvocation candidate = queue.removeFirst();
                if (candidate.batch.isResolved) {
                    continue;
                }
                if (Duration.between(candidate.member.queuedAt, Instant.now()).compareTo(queueTimeout) > 0) {
                    candidate.member.fail(MemberState.FAILED, "REMOTE_OVERLOADED",
                            "Remote invocation queue wait timed out");
                    expired.add(candidate);
                } else {
                    activeCount++;
                    candidate.member.state = MemberState.RUNNING;
                    candidate.member.startedAt = Instant.now();
                    next = candidate;
                }
            }
        }
        if (shouldApplyOutcome) {
            logMemberState(batch, member);
        }
        logExpiredInvocations(expired);
        if (next != null) {
            boolean isResolved;
            synchronized (lock) {
                isResolved = next.batch.isResolved;
            }
            if (isResolved) {
                start(next);
                finishBatchIfSettled(batch);
                return;
            }
            logMemberState(next.batch, next.member);
            start(next);
        }
        finishBatchIfSettled(batch);
    }

    private void logExpiredInvocations(List<PendingInvocation> expired) {
        for (PendingInvocation candidate : expired) {
            synchronized (lock) {
                if (candidate.batch.isResolved) {
                    continue;
                }
            }
            logMemberState(candidate.batch, candidate.member);
            finishBatchIfSettled(candidate.batch);
        }
    }

    private static void applyOutcome(Member member, RemoteCallOutcome outcome, Throwable error) {
        member.completedAt = Instant.now();
        if (error != null) {
            Throwable cause = unwrap(error);
            if (cause instanceof TimeoutException) {
                member.fail(MemberState.TIMED_OUT, "REMOTE_TIMEOUT", "Remote invocation timed out");
            } else if (cause instanceof RejectedExecutionException) {
                member.fail(MemberState.FAILED, "REMOTE_OVERLOADED", safeMessage(cause));
            } else if (isRateLimited(cause)) {
                member.fail(MemberState.FAILED, "REMOTE_RATE_LIMITED", safeMessage(cause));
            } else if (isProtocolFailure(cause)) {
                member.fail(MemberState.FAILED, "REMOTE_PROTOCOL_ERROR", safeMessage(cause));
            } else {
                member.fail(MemberState.FAILED, "REMOTE_UNAVAILABLE", safeMessage(cause));
            }
            return;
        }
        if (outcome == null) {
            member.fail(MemberState.FAILED, "REMOTE_PROTOCOL_ERROR", "Remote call returned no outcome");
            return;
        }
        if (outcome.remoteTaskId() != null && !outcome.remoteTaskId().isBlank()) {
            member.remoteTaskId = outcome.remoteTaskId();
        }
        member.resultCategory = outcome.resultCategory();
        if (outcome.remoteState() == TaskState.TASK_STATE_INPUT_REQUIRED
                || outcome.remoteState() == TaskState.TASK_STATE_AUTH_REQUIRED) {
            member.state = MemberState.INPUT_REQUIRED;
            member.inputPrompt = outcome.inputPrompt() == null ? "Remote agent requires input" : outcome.inputPrompt();
        } else if (outcome.remoteState() == TaskState.TASK_STATE_COMPLETED) {
            member.state = MemberState.COMPLETED;
            member.result = outcome.result() == null ? "" : outcome.result();
        } else {
            String message = outcome.result() == null || outcome.result().isBlank()
                    ? "Remote task did not complete"
                    : outcome.result();
            member.fail(MemberState.FAILED, outcome.resultCategory(), message);
        }
    }

    private static RemoteCallOutcome callbackOutcome(Task task) {
        TaskStatus status = task.status();
        TaskState state = status == null ? null : status.state();
        String statusText = status == null || status.message() == null ? "" : extractText(status.message().parts());
        String taskText = extractTaskResult(task);
        if (isResultBearingNonTerminalState(state) && (!taskText.isBlank() || !statusText.isBlank())) {
            state = TaskState.TASK_STATE_COMPLETED;
        }
        if (state == TaskState.TASK_STATE_INPUT_REQUIRED || state == TaskState.TASK_STATE_AUTH_REQUIRED) {
            String inputPrompt = statusText.isBlank() ? "Remote agent requires input" : statusText;
            return new RemoteCallOutcome(task.id(), state, resultCategory(state), null, inputPrompt);
        }
        String resultText = state == TaskState.TASK_STATE_COMPLETED
                ? (taskText.isBlank() ? statusText : taskText)
                : (statusText.isBlank() ? taskText : statusText);
        return new RemoteCallOutcome(task.id(), state, resultCategory(state), resultText, null);
    }

    private static String resultCategory(TaskState state) {
        if (state == null) {
            return "REMOTE_PROTOCOL_ERROR";
        }
        return switch (state) {
            case TASK_STATE_COMPLETED -> "COMPLETED";
            case TASK_STATE_INPUT_REQUIRED, TASK_STATE_AUTH_REQUIRED -> "INPUT_REQUIRED";
            case TASK_STATE_REJECTED -> "REMOTE_REJECTED";
            case TASK_STATE_FAILED -> "REMOTE_BUSINESS_FAILURE";
            default -> "REMOTE_PROTOCOL_ERROR";
        };
    }

    private static boolean isResultBearingNonTerminalState(TaskState state) {
        return state == null || state == TaskState.UNRECOGNIZED || state == TaskState.TASK_STATE_SUBMITTED
                || state == TaskState.TASK_STATE_WORKING;
    }

    private static String shadowState(Batch batch) {
        boolean hasWaitingMember = batch.members.stream()
                .anyMatch(member -> member.state == MemberState.INPUT_REQUIRED);
        return hasWaitingMember ? "WAITING_INPUT" : "READY_TO_RESUME";
    }

    private static String extractTaskResult(Task task) {
        return A2aPartContent.extractTaskResult(task);
    }

    private static String extractText(List<Part<?>> parts) {
        if (parts == null || parts.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Part<?> part : parts) {
            if (part instanceof TextPart textPart) {
                text.append(textPart.text());
            }
        }
        return text.toString();
    }

    private void finishBatchIfSettled(Batch batch) {
        boolean isSettled;
        synchronized (lock) {
            isSettled = !batch.isResolved && batch.members.stream()
                    .noneMatch(member -> member.state == MemberState.QUEUED || member.state == MemberState.RUNNING);
            if (isSettled) {
                batch.isResolved = true;
            }
        }
        if (!isSettled) {
            return;
        }
        try {
            BatchResolution resolution = resolveSettledBatch(batch);
            batch.observer.awaitDrained();
            synchronized (lock) {
                activeByParent.remove(batch.parentTaskId, batch);
            }
            batch.completion.complete(resolution);
        } catch (RuntimeException ex) {
            synchronized (lock) {
                activeByParent.remove(batch.parentTaskId, batch);
            }
            batch.completion.completeExceptionally(ex);
        }
    }

    private BatchResolution resolveSettledBatch(Batch batch) {
        boolean hasWaitingMember = batch.members.stream()
                .anyMatch(member -> member.state == MemberState.INPUT_REQUIRED);
        if (hasWaitingMember) {
            Map<String, Object> interrupt = publicInterrupt(batch);
            saveShadow(batch, "WAITING_INPUT");
            return new BatchResolution(batch.batchId, false, Map.of(), interrupt, batch.shouldResume);
        }
        if (batch.shouldResume) {
            saveShadow(batch, "READY_TO_RESUME");
        } else {
            deleteShadow(batch.parentTaskId);
        }
        return snapshotResolution(batch);
    }

    private void deleteShadow(String parentTaskId) {
        try {
            taskStore.delete(shadowTaskId(parentTaskId));
        } catch (IllegalStateException ex) {
            log.warn("Failed to delete shadow task parentTaskId={}", parentTaskId, ex);
        }
    }

    private void saveShadow(Batch batch, String state) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("batchId", batch.batchId);
        snapshot.put("parentTaskId", batch.parentTaskId);
        snapshot.put("resume", batch.shouldResume);
        snapshot.put("state", state);
        List<Map<String, Object>> members = new ArrayList<>();
        for (Member member : batch.members) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("index", member.index);
            value.put("toolCallId", member.toolCallId);
            value.put("toolName", member.toolName);
            value.put("agentName", member.agentName);
            value.put("state", member.state.name());
            putIfNotBlank(value, "remoteTaskId", member.remoteTaskId);
            putIfNotBlank(value, "resultCategory", member.resultCategory);
            if (member.state == MemberState.COMPLETED && member.result != null) {
                value.put("result", member.result);
            } else {
                if (member.state != MemberState.INPUT_REQUIRED) {
                    value.put("result", toolResult(member));
                }
            }
            putIfNotBlank(value, "inputPrompt", member.inputPrompt);
            members.add(value);
        }
        snapshot.put("members", members);
        taskStore.save(Task.builder().id(shadowTaskId(batch.parentTaskId)).contextId(batch.request.getConversationId())
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of(REMOTE_BATCH, snapshot)).build(), true);
        replayEarlyCallbacks(batch);
    }

    private void replayEarlyCallbacks(Batch batch) {
        List<Task> callbacks = new ArrayList<>();
        synchronized (lock) {
            for (Member member : batch.members) {
                if (member.remoteTaskId == null || member.remoteTaskId.isBlank()) {
                    continue;
                }
                Task callback = earlyCallbacksByRemoteTaskId.remove(member.remoteTaskId);
                if (callback != null) {
                    callbacks.add(callback);
                }
            }
        }
        if (callbacks.isEmpty()) {
            return;
        }
        Task shadow = taskStore.get(shadowTaskId(batch.parentTaskId));
        for (Task callback : callbacks) {
            if (!isBatchShadow(shadow)) {
                return;
            }
            Map<?, ?> rawBatch = (Map<?, ?>) shadow.metadata().get(REMOTE_BATCH);
            if (recoverShadow(shadow, rawBatch, callback)) {
                shadow = taskStore.get(shadowTaskId(batch.parentTaskId));
            }
        }
    }

    void completeResume(ServeRequest request) {
        Map<String, Object> metadata = request.getMetadata();
        if (!(metadata.get("runtime.remoteToolResults") instanceof Map<?, ?>)) {
            return;
        }
        String batchId = stringValue(metadata.get(REMOTE_BATCH_ID));
        if (batchId.isBlank()) {
            return;
        }
        String parentTaskId = parentTaskId(request);
        Task shadow = taskStore.get(shadowTaskId(parentTaskId));
        if (shadow != null && shadow.metadata() != null
                && shadow.metadata().get(REMOTE_BATCH) instanceof Map<?, ?> batch
                && batchId.equals(stringValue(batch.get("batchId")))) {
            taskStore.delete(shadow.id());
        }
        releaseCoreResumeClaim(parentTaskId, batchId);
    }

    void abortResume(ServeRequest request) {
        Map<String, Object> metadata = request.getMetadata();
        if (metadata.get("runtime.remoteToolResults") instanceof Map<?, ?>) {
            releaseCoreResumeClaim(parentTaskId(request), stringValue(metadata.get(REMOTE_BATCH_ID)));
        }
    }

    private QueryStreamObserver memberOutputObserver(Batch batch, Member member) {
        return new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                synchronized (lock) {
                    if (batch.isResolved) {
                        return;
                    }
                }
                if (chunk != null) {
                    forwardRemoteOutput(batch, member, chunk.getData());
                }
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public boolean isCancelled() {
                return batch.observer.isCancelled();
            }
        };
    }

    private void forwardRemoteOutput(Batch batch, Member member, Object content) {
        Map<String, Object> projection = new LinkedHashMap<>();
        projection.put("kind", "remote_agent_output");
        projection.put("batchId", batch.batchId);
        projection.put("toolCallId", member.toolCallId);
        projection.put("target", member.agentName.isBlank() ? member.toolName : member.agentName);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("content", content == null ? "" : content);
        data.put("projection", projection);
        try {
            batch.observer.onNext(new QueryChunk(QueryChunk.TYPE_REMOTE_AGENT_OUTPUT, data));
        } catch (IllegalStateException ex) {
            failRemoteOutput(batch, ex);
        }
    }

    private void failRemoteOutput(Batch batch, IllegalStateException cause) {
        boolean isBatchFailed = false;
        synchronized (lock) {
            if (!batch.isResolved) {
                batch.isResolved = true;
                activeByParent.remove(batch.parentTaskId, batch);
                queue.removeIf(invocation -> invocation.batch == batch);
                isBatchFailed = true;
            }
        }
        if (isBatchFailed) {
            batch.completion.completeExceptionally(cause);
        }
    }

    private void startNextQueuedAfterReleasedSlot() {
        PendingInvocation next = null;
        List<PendingInvocation> expired = new ArrayList<>();
        synchronized (lock) {
            while (activeCount < maxConcurrency && !queue.isEmpty() && next == null) {
                PendingInvocation candidate = queue.removeFirst();
                if (candidate.batch.isResolved) {
                    continue;
                }
                if (Duration.between(candidate.member.queuedAt, Instant.now()).compareTo(queueTimeout) > 0) {
                    candidate.member.fail(MemberState.FAILED, "REMOTE_OVERLOADED",
                            "Remote invocation queue wait timed out");
                    expired.add(candidate);
                } else {
                    activeCount++;
                    candidate.member.state = MemberState.RUNNING;
                    candidate.member.startedAt = Instant.now();
                    next = candidate;
                }
            }
        }
        for (PendingInvocation candidate : expired) {
            logMemberState(candidate.batch, candidate.member);
            finishBatchIfSettled(candidate.batch);
        }
        if (next != null) {
            logMemberState(next.batch, next.member);
            start(next);
        }
    }

    private static void logMemberState(Batch batch, Member member) {
        long latencyMs = 0L;
        if (member.startedAt != null) {
            Instant end = member.completedAt != null ? member.completedAt : Instant.now();
            latencyMs = Math.max(0, Duration.between(member.startedAt, end).toMillis());
        }
        String target = member.agentName.isBlank() ? member.toolName : member.agentName;
        log.info(
                "Remote invocation state parentTaskId={} conversationId={} batchId={} toolCallId={} "
                        + "remoteAgentId={} state={} latencyMs={}",
                batch.parentTaskId, batch.request.getConversationId(), batch.batchId, member.toolCallId, target,
                member.state, latencyMs);
    }

    private Batch parseBatch(Map<String, Object> interrupt, ServeRequest request, String parentTaskId,
            SerialObserver observer) {
        List<Map<String, Object>> items = interruptItems(interrupt);
        List<Member> members = new ArrayList<>();
        Set<String> toolCallIds = new LinkedHashSet<>();
        Boolean isBatchResume = null;
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> item = items.get(i);
            String toolCallId = stringValue(item.get("toolCallId"));
            if (toolCallId.isBlank()) {
                throw new IllegalArgumentException("CORE_INTERRUPT_CORRELATION_MISSING");
            }
            if (!toolCallIds.add(toolCallId)) {
                throw new IllegalArgumentException("CORE_INTERRUPT_CORRELATION_CONFLICT: " + toolCallId);
            }
            Map<String, Object> context = item.get("context") instanceof Map<?, ?> rawContext
                    ? copyMap(rawContext)
                    : Map.of();
            if (!"a2a_delegate".equals(stringValue(context.get("_interrupt_kind")))) {
                throw new IllegalArgumentException("CORE_INTERRUPT_KIND_MIXED_UNSUPPORTED");
            }
            boolean isMemberResume = !(context.get("resume") instanceof Boolean isResumeFlag) || isResumeFlag;
            if (isBatchResume != null && isBatchResume != isMemberResume) {
                throw new IllegalArgumentException("CORE_INTERRUPT_RESUME_MIXED_UNSUPPORTED");
            }
            if (isBatchResume == null) {
                isBatchResume = isMemberResume;
            }
            int index = item.get("index") instanceof Number number ? number.intValue() : i;
            Member member = new Member(index, toolCallId, stringValue(item.get("toolName")),
                    stringValue(context.get("agentName")), stringValue(item.get("message")));
            members.add(member);
        }
        members.sort(java.util.Comparator.comparingInt(member -> member.index));
        return new Batch(UUID.randomUUID().toString(), parentTaskId, request, observer, members,
                isBatchResume == null || isBatchResume);
    }

    private Batch restoreBatch(Map<?, ?> rawBatch, ServeRequest request, String parentTaskId, SerialObserver observer) {
        Object rawMembers = rawBatch.get("members");
        if (!(rawMembers instanceof List<?> values)) {
            throw new IllegalStateException("REMOTE_BATCH_SNAPSHOT_INVALID");
        }
        List<Member> members = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> rawMember)) {
                throw new IllegalStateException("REMOTE_BATCH_MEMBER_INVALID");
            }
            int index = rawMember.get("index") instanceof Number number ? number.intValue() : members.size();
            Member member = new Member(index, stringValue(rawMember.get("toolCallId")),
                    stringValue(rawMember.get("toolName")), stringValue(rawMember.get("agentName")), "");
            member.state = MemberState.valueOf(stringValue(rawMember.get("state")));
            member.remoteTaskId = stringValue(rawMember.get("remoteTaskId"));
            member.resultCategory = optionalNonBlank(stringValue(rawMember.get("resultCategory"))).orElse(null);
            member.result = rawMember.get("result");
            if (member.state != MemberState.COMPLETED && member.result instanceof Map<?, ?> error) {
                member.errorMessage = optionalNonBlank(stringValue(error.get("message"))).orElse(null);
                if (member.resultCategory == null) {
                    member.resultCategory = optionalNonBlank(stringValue(error.get("code"))).orElse(null);
                }
            }
            member.inputPrompt = optionalNonBlank(stringValue(rawMember.get("inputPrompt"))).orElse(null);
            members.add(member);
        }
        members.sort(java.util.Comparator.comparingInt(member -> member.index));
        String batchId = stringValue(rawBatch.get("batchId"));
        boolean shouldResume = !(rawBatch.get("resume") instanceof Boolean isResumeFlag) || isResumeFlag;
        return new Batch(batchId, parentTaskId, request, observer, members, shouldResume);
    }

    private static List<Map<String, Object>> interruptItems(Map<String, Object> interrupt) {
        Object rawItems = interrupt.get("items");
        if (rawItems instanceof List<?> values) {
            List<Map<String, Object>> items = new ArrayList<>();
            for (Object value : values) {
                if (!(value instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("CORE_INTERRUPT_BATCH_INVALID");
                }
                items.add(copyMap(map));
            }
            if (items.isEmpty()) {
                throw new IllegalArgumentException("CORE_INTERRUPT_BATCH_EMPTY");
            }
            return items;
        }
        return List.of(new LinkedHashMap<>(interrupt));
    }

    private static Map<String, Object> publicInterrupt(Batch batch) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Member member : batch.members) {
            if (member.state != MemberState.INPUT_REQUIRED) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("toolCallId", member.toolCallId);
            putIfNotBlank(item, "toolName", member.toolName);
            item.put("message", member.inputPrompt == null ? "Remote agent requires input" : member.inputPrompt);
            items.add(item);
        }
        Map<String, Object> interrupt = new LinkedHashMap<>();
        interrupt.put("message",
                items.size() == 1 ? items.get(0).get("message") : "Multiple remote agents require input");
        interrupt.put("items", items);
        return interrupt;
    }

    private static Object toolResult(Member member) {
        if (member.state == MemberState.COMPLETED) {
            return member.result == null ? "" : member.result;
        }
        if (member.result instanceof Map<?, ?>) {
            return member.result;
        }
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("ok", false);
        error.put("code", member.resultCategory == null ? "REMOTE_FAILED" : member.resultCategory);
        error.put("message", member.errorMessage == null ? "Remote invocation failed" : member.errorMessage);
        error.put("remoteAgentId", member.agentName.isBlank() ? member.toolName : member.agentName);
        return error;
    }

    private static BatchResolution snapshotResolution(Batch batch) {
        boolean hasWaitingMember = batch.members.stream()
                .anyMatch(member -> member.state == MemberState.INPUT_REQUIRED);
        if (hasWaitingMember) {
            return new BatchResolution(batch.batchId, false, Map.of(), publicInterrupt(batch), batch.shouldResume);
        }
        Map<String, Object> results = new LinkedHashMap<>();
        batch.members.forEach(member -> results.put(member.toolCallId, toolResult(member)));
        return new BatchResolution(batch.batchId, true, results, Map.of(), batch.shouldResume);
    }

    boolean claimCoreResume(ServeRequest request, String batchId) {
        String parentTaskId = parentTaskId(request);
        synchronized (lock) {
            if (coreResumeClaims.containsKey(parentTaskId)) {
                return false;
            }
            coreResumeClaims.put(parentTaskId, batchId);
        }
        return true;
    }

    private void releaseCoreResumeClaim(String parentTaskId, String batchId) {
        if (batchId == null || batchId.isBlank()) {
            return;
        }
        synchronized (lock) {
            coreResumeClaims.remove(parentTaskId, batchId);
        }
    }

    private static Map<String, Object> outboundMetadata(Map<String, Object> source) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> {
                if (!RESERVED_METADATA.contains(key)) {
                    metadata.put(key, value);
                }
            });
        }
        return metadata;
    }

    private String shadowTaskId(String parentTaskId) {
        return SHADOW_PREFIX + agentId + ":" + parentTaskId;
    }

    private static String parentTaskId(ServeRequest request) {
        Object value = request.getMetadata().get(PARENT_TASK_ID);
        if (value instanceof String parentTaskId && !parentTaskId.isBlank()) {
            return parentTaskId;
        }
        return request.getConversationId();
    }

    private static Map<String, String> targetedInputs(ServeRequest request) {
        Object value = request.getMetadata().get(REMOTE_TOOL_INPUTS);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> inputs = new LinkedHashMap<>();
        map.forEach((key, input) -> inputs.put(String.valueOf(key), input == null ? "" : String.valueOf(input)));
        return inputs;
    }

    private static Map<String, Object> copyMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private static boolean isRateLimited(Throwable error) {
        String message = safeMessage(error).toLowerCase(java.util.Locale.ROOT);
        return message.contains("429") || message.contains("rate limit") || message.contains("too many requests");
    }

    private static boolean isProtocolFailure(Throwable error) {
        String message = safeMessage(error).toLowerCase(java.util.Locale.ROOT);
        return message.contains("json-rpc") || message.contains("jsonrpc") || message.contains("protocol")
                || message.contains("malformed") || message.contains("parse error");
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Optional<String> optionalNonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String remoteContextId(Batch batch, Member member) {
        String parentContextId = batch.request.getConversationId();
        if (parentContextId == null || parentContextId.isBlank()) {
            parentContextId = batch.parentTaskId;
        }
        if (batch.members.size() <= 1) {
            return parentContextId;
        }
        return parentContextId + ":" + batch.batchId + ":" + member.toolCallId;
    }

    private static void putIfNotBlank(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isBlank()) {
            map.put(key, value);
        }
    }

    /**
     * Result returned to the orchestrator after all currently runnable members settle.
     *
     * @param batchId           snapshot id, also stored on the shadow task
     * @param isReadyToResume   whether every member settled and the parent agent can resume
     * @param results           per-toolCallId tool results, populated when {@code isReadyToResume}
     * @param interrupt         input-required interrupt to forward to the client, populated otherwise
     * @param shouldResume      whether the parent agent should be re-invoked with the remote answers
     *                          as tool results ({@code true}, tool-call path) or whether the remote
     *                          answer is this layer's terminal output ({@code false}, intent-workflow
     *                          path). Defaults to {@code true} when the interrupt payload omits it.
     */
    record BatchResolution(String batchId, boolean isReadyToResume, Map<String, Object> results,
            Map<String, Object> interrupt, boolean shouldResume) {
    }

    private enum MemberState {
        QUEUED, RUNNING, COMPLETED, INPUT_REQUIRED, FAILED, TIMED_OUT
    }

    private static final class Batch {
        private final String batchId;

        private final String parentTaskId;

        private final ServeRequest request;

        private final SerialObserver observer;

        private final List<Member> members;

        private final boolean shouldResume;

        private final CompletableFuture<BatchResolution> completion = new CompletableFuture<>();

        private boolean isResolved;

        private Batch(String batchId, String parentTaskId, ServeRequest request, SerialObserver observer,
                List<Member> members, boolean shouldResume) {
            this.batchId = batchId;
            this.parentTaskId = parentTaskId;
            this.request = request;
            this.observer = observer;
            this.members = members;
            this.shouldResume = shouldResume;
        }
    }

    private static final class Member {
        private final int index;

        private final String toolCallId;

        private final String toolName;

        private final String agentName;

        private String message;

        private volatile MemberState state = MemberState.QUEUED;

        private String remoteTaskId = "";

        private Object result;

        private String resultCategory;

        private String inputPrompt;

        private String errorMessage;

        private Instant queuedAt = Instant.now();

        private Instant startedAt;

        private Instant completedAt;

        private Member(int index, String toolCallId, String toolName, String agentName, String message) {
            this.index = index;
            this.toolCallId = toolCallId;
            this.toolName = toolName;
            this.agentName = agentName;
            this.message = message;
        }

        private void fail(MemberState failedState, String category, String message) {
            this.resultCategory = category;
            this.errorMessage = message;
            this.completedAt = Instant.now();
            this.state = failedState;
        }
    }

    private record PendingInvocation(Batch batch, Member member) {
    }

    /** Serializes callbacks from concurrent remote futures without owning an executor. */
    private static final class SerialObserver implements QueryStreamObserver {
        private final QueryStreamObserver delegate;

        private final Deque<QueryChunk> pending = new ArrayDeque<>();

        private boolean isDraining;

        private SerialObserver(QueryStreamObserver delegate) {
            this.delegate = delegate;
        }

        @Override
        public void onNext(QueryChunk chunk) {
            boolean isDrainOwner = false;
            synchronized (pending) {
                pending.addLast(chunk);
                if (!isDraining) {
                    isDraining = true;
                    isDrainOwner = true;
                }
            }
            if (!isDrainOwner) {
                return;
            }
            while (true) {
                QueryChunk next;
                synchronized (pending) {
                    next = pending.pollFirst();
                    if (next == null) {
                        isDraining = false;
                        pending.notifyAll();
                        return;
                    }
                }
                try {
                    delegate.onNext(next);
                } catch (RuntimeException | Error ex) {
                    synchronized (pending) {
                        pending.clear();
                        isDraining = false;
                        pending.notifyAll();
                    }
                    throw ex;
                }
            }
        }

        private void awaitDrained() {
            synchronized (pending) {
                while (isDraining) {
                    try {
                        pending.wait();
                    } catch (InterruptedException ex) {
                        throw new IllegalStateException("Interrupted while waiting for remote stream events", ex);
                    }
                }
            }
        }

        @Override
        public void onComplete() {
        }

        @Override
        public void onError(Throwable error) {
            delegate.onError(error);
        }

        @Override
        public boolean isCancelled() {
            return delegate.isCancelled();
        }
    }
}
