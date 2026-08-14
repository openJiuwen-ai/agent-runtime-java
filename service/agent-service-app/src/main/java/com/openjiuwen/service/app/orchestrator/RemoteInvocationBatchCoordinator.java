/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller.EventObserver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.Member;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.MemberState;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationCoordinatorState.Dispatch;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationCoordinatorState.InvocationCompletion;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationCoordinatorState.PendingInvocation;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationCoordinatorState.Submission;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

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

    private final Duration queueTimeout;

    private final RemoteInvocationBatchMapper batchMapper = new RemoteInvocationBatchMapper();

    private final RemoteInvocationCoordinatorState state;

    private final Consumer<ServeRequest> continuation;

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
        this(taskStore, client, agentId, maxConcurrency, maxQueueSize, queueTimeoutSeconds, request -> {
        });
    }

    RemoteInvocationBatchCoordinator(TaskStore taskStore, RemoteAgentCaller client, String agentId, int maxConcurrency,
            int maxQueueSize, long queueTimeoutSeconds, Consumer<ServeRequest> continuation) {
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
        this.queueTimeout = Duration.ofSeconds(queueTimeoutSeconds);
        this.state = new RemoteInvocationCoordinatorState(maxConcurrency, maxQueueSize, queueTimeout);
        this.continuation = continuation;
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
        RemoteInvocationBatch batch;
        try {
            batch = batchMapper.parse(interrupt, request, parentTaskId, new SerialQueryStreamObserver(observer));
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

    private Optional<CompletableFuture<BatchResolution>> registerBatch(RemoteInvocationBatch batch) {
        String parentTaskId = batch.parentTaskId;
        if (state.hasActiveBatch(parentTaskId)) {
            return Optional.of(CompletableFuture
                    .failedFuture(new IllegalStateException("REMOTE_BATCH_ALREADY_ACTIVE: " + parentTaskId)));
        }
        Task persisted = taskStore.get(shadowTaskId(parentTaskId));
        if (isBatchShadow(persisted)) {
            return Optional.of(CompletableFuture
                    .failedFuture(new IllegalStateException("REMOTE_BATCH_ALREADY_PENDING: " + parentTaskId)));
        }
        if (!state.registerBatch(batch)) {
            return Optional.of(CompletableFuture
                    .failedFuture(new IllegalStateException("REMOTE_BATCH_ALREADY_ACTIVE: " + parentTaskId)));
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
        RemoteInvocationBatch batch = batchMapper.restore(rawBatch, request, parentTaskId,
                new SerialQueryStreamObserver(observer));
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
        state.rememberEarlyCallback(task);
        return false;
    }

    private boolean recoverShadow(Task shadow, Map<?, ?> rawBatch, Task task) {
        String parentTaskId = stringValue(rawBatch.get("parentTaskId"));
        ServeRequest request = new ServeRequest();
        request.setConversationId(shadow.contextId());
        request.setMetadata(parentTaskId.isBlank() ? Map.of() : Map.of(PARENT_TASK_ID, parentTaskId));
        request = batchMapper.continuationRequest(rawBatch, request);
        Map<String, Object> requestMetadata = new LinkedHashMap<>(request.getMetadata());
        requestMetadata.put(PARENT_TASK_ID, parentTaskId);
        requestMetadata.put(REMOTE_BATCH_ID, stringValue(rawBatch.get("batchId")));
        request.setMetadata(requestMetadata);
        RemoteInvocationBatch batch = batchMapper.restore(rawBatch, request, parentTaskId,
                new SerialQueryStreamObserver(NOOP_OBSERVER));
        Optional<Member> matched = batch.members.stream().filter(member -> task.id().equals(member.remoteTaskId))
                .findFirst();
        if (matched.isEmpty()) {
            return false;
        }
        Member member = matched.get();
        if (member.state == MemberState.COMPLETED) {
            return true;
        }
        batchMapper.applyOutcome(member, batchMapper.callbackOutcome(task), null);
        String shadowState = batchMapper.shadowState(batch);
        saveShadow(batch, shadowState);
        if ("READY_TO_RESUME".equals(shadowState)) {
            submitContinuation(batch.request);
        }
        return true;
    }

    private void submitContinuation(ServeRequest request) {
        continuation.accept(request);
    }

    private Optional<CompletableFuture<BatchResolution>> resumeReadyBatch(RemoteInvocationBatch batch,
            Map<String, String> targetedInputs) {
        Set<String> memberIds = new LinkedHashSet<>();
        batch.members.forEach(member -> memberIds.add(member.toolCallId));
        for (String toolCallId : targetedInputs.keySet()) {
            if (!memberIds.contains(toolCallId)) {
                return Optional.of(CompletableFuture
                        .failedFuture(new IllegalArgumentException("REMOTE_TOOL_INPUT_TARGET_UNKNOWN: " + toolCallId)));
            }
        }
        return Optional.of(CompletableFuture.completedFuture(batchMapper.resolution(batch)));
    }

    private Optional<CompletableFuture<BatchResolution>> resumeWaitingBatch(RemoteInvocationBatch batch,
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
        if (!state.registerBatch(batch)) {
            return Optional.of(CompletableFuture
                    .failedFuture(new IllegalStateException("REMOTE_BATCH_ALREADY_ACTIVE: " + parentTaskId)));
        }
        selected.forEach(member -> submit(new PendingInvocation(batch, member)));
        return Optional.of(batch.completion);
    }

    private static boolean isBatchShadow(Task task) {
        return task != null && task.status() != null && task.status().state() == TaskState.TASK_STATE_INPUT_REQUIRED
                && task.metadata() != null && task.metadata().get(REMOTE_BATCH) instanceof Map<?, ?>;
    }

    private void submit(PendingInvocation invocation) {
        Submission submission = state.submit(invocation);
        if (submission == Submission.IGNORED) {
            return;
        }
        logMemberState(invocation.batch(), invocation.member());
        if (submission == Submission.START) {
            start(invocation);
            return;
        }
        if (submission == Submission.QUEUED) {
            CompletableFuture.delayedExecutor(queueTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .execute(() -> expireQueued(invocation));
            return;
        }
        if (submission == Submission.OVERLOADED) {
            finishBatchIfSettled(invocation.batch());
        }
    }

    private void expireQueued(PendingInvocation invocation) {
        if (!state.expireQueued(invocation)) {
            return;
        }
        logMemberState(invocation.batch(), invocation.member());
        finishBatchIfSettled(invocation.batch());
    }

    private void start(PendingInvocation invocation) {
        Member member = invocation.member();
        RemoteInvocationBatch batch = invocation.batch();
        if (!state.prepareStart(invocation)) {
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
        CompletableFuture<RemoteCallOutcome> future;
        try {
            future = client.callOutcome(call, new MemberEventObserver(batch, member));
        } catch (RuntimeException ex) {
            finishInvocation(invocation, null, ex);
            return;
        }
        future.whenComplete((outcome, error) -> finishInvocation(invocation, outcome, error));
    }

    private void finishInvocation(PendingInvocation invocation, RemoteCallOutcome outcome, Throwable error) {
        RemoteInvocationBatch batch = invocation.batch();
        Member member = invocation.member();
        InvocationCompletion completion = state.finishInvocation(invocation,
                () -> batchMapper.applyOutcome(member, outcome, error));
        if (completion.isOutcomeApplied()) {
            logMemberState(batch, member);
        }
        logExpiredInvocations(completion.expired());
        PendingInvocation next = completion.next();
        if (next != null) {
            if (state.isResolved(next.batch())) {
                start(next);
                finishBatchIfSettled(batch);
                return;
            }
            logMemberState(next.batch(), next.member());
            start(next);
        }
        finishBatchIfSettled(batch);
    }

    private void logExpiredInvocations(List<PendingInvocation> expired) {
        for (PendingInvocation candidate : expired) {
            if (state.isResolved(candidate.batch())) {
                continue;
            }
            logMemberState(candidate.batch(), candidate.member());
            finishBatchIfSettled(candidate.batch());
        }
    }

    private void finishBatchIfSettled(RemoteInvocationBatch batch) {
        if (!state.settle(batch)) {
            return;
        }
        try {
            BatchResolution resolution = resolveSettledBatch(batch);
            batch.observer.awaitDrained();
            state.removeBatch(batch);
            batch.completion.complete(resolution);
        } catch (RuntimeException ex) {
            state.removeBatch(batch);
            batch.completion.completeExceptionally(ex);
        }
    }

    private BatchResolution resolveSettledBatch(RemoteInvocationBatch batch) {
        boolean hasWaitingMember = batch.members.stream()
                .anyMatch(member -> member.state == MemberState.INPUT_REQUIRED);
        if (hasWaitingMember) {
            saveShadow(batch, "WAITING_INPUT");
            return batchMapper.resolution(batch);
        }
        if (batch.shouldResume) {
            saveShadow(batch, "READY_TO_RESUME");
        } else {
            deleteShadow(batch.parentTaskId);
        }
        return batchMapper.resolution(batch);
    }

    private void deleteShadow(String parentTaskId) {
        try {
            taskStore.delete(shadowTaskId(parentTaskId));
        } catch (IllegalStateException ex) {
            log.warn("Failed to delete shadow task parentTaskId={}", parentTaskId, ex);
        }
    }

    private void saveShadow(RemoteInvocationBatch batch, String state) {
        Map<String, Object> snapshot = batchMapper.snapshot(batch, state);
        taskStore.save(Task.builder().id(shadowTaskId(batch.parentTaskId)).contextId(batch.request.getConversationId())
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of(REMOTE_BATCH, snapshot)).build(), true);
        replayEarlyCallbacks(batch);
    }

    private void replayEarlyCallbacks(RemoteInvocationBatch batch) {
        List<Task> callbacks = state.takeEarlyCallbacks(batch);
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

    private final class MemberEventObserver implements EventObserver {
        private final RemoteInvocationBatch batch;

        private final Member member;

        private final boolean shouldProjectEvents;

        private boolean isDelegationPublished;

        private TaskState lastStatus;

        private List<Part<?>> lastStatusParts = List.of();

        private MemberEventObserver(RemoteInvocationBatch batch, Member member) {
            this.batch = batch;
            this.member = member;
            this.shouldProjectEvents = batch.request.isStream();
        }

        private synchronized boolean observeRemoteTask(String remoteTaskId) {
            if (state.isResolved(batch)) {
                return false;
            }
            if (remoteTaskId == null || remoteTaskId.isBlank()) {
                throw new IllegalArgumentException("RemoteAgentCaller event has a blank task id");
            }
            if (member.remoteTaskId != null && !member.remoteTaskId.isBlank()
                    && !member.remoteTaskId.equals(remoteTaskId)) {
                throw new IllegalStateException("RemoteAgentCaller event task id does not match the call task id");
            }
            if (member.remoteTaskId == null || member.remoteTaskId.isBlank()) {
                state.captureRemoteTaskId(batch, member, remoteTaskId);
            }
            if (shouldProjectEvents && !isDelegationPublished) {
                publishDelegation(batch, member, remoteTaskId);
                isDelegationPublished = true;
            }
            return true;
        }

        @Override
        public synchronized void onStatus(TaskStatusUpdateEvent event) {
            if (!observeRemoteTask(event.taskId()) || !shouldProjectEvents || state.isResolved(batch)) {
                return;
            }
            TaskState currentStatus = event.status().state();
            String normalized = normalizeState(currentStatus);
            List<Part<?>> parts = event.status().message() == null || event.status().message().parts() == null
                    || event.status().message().parts().isEmpty()
                            ? List.of(new TextPart(normalized))
                            : event.status().message().parts();
            if (lastStatus != null && (lastStatus.isFinal()
                    || currentStatus == lastStatus && parts.equals(lastStatusParts))) {
                return;
            }
            lastStatus = currentStatus;
            lastStatusParts = List.copyOf(parts);
            Artifact artifact = Artifact.builder()
                    .artifactId("status:" + remoteAgentId(member) + ":" + event.taskId() + ":" + UUID.randomUUID())
                    .parts(parts)
                    .metadata(statusMetadata(remoteAgentId(member), event.taskId(), currentStatus)).build();
            forwardRemoteArtifact(batch, member, new TaskArtifactUpdateEvent(event.taskId(), artifact,
                    event.contextId(), false, true, event.metadata()));
        }

        @Override
        public void onArtifact(TaskArtifactUpdateEvent event) {
            if (!observeRemoteTask(event.taskId()) || !shouldProjectEvents || state.isResolved(batch)) {
                return;
            }
            forwardRemoteArtifact(batch, member, event);
        }
    }

    private void publishDelegation(RemoteInvocationBatch batch, Member member, String remoteTaskId) {
        String text = member.message == null || member.message.isBlank()
                ? "任务已委派给 " + remoteAgentId(member)
                : member.message;
        Artifact artifact = Artifact.builder().artifactId("delegation:" + batch.parentTaskId + ":" + remoteTaskId)
                .parts(new TextPart(text))
                .metadata(delegationMetadata(member.toolCallId, agentId, batch.parentTaskId,
                        remoteAgentId(member), remoteTaskId)).build();
        forwardRemoteArtifact(batch, member, new TaskArtifactUpdateEvent(remoteTaskId, artifact,
                remoteContextId(batch, member), false, true, Map.of()));
    }

    private void forwardRemoteArtifact(RemoteInvocationBatch batch, Member member, TaskArtifactUpdateEvent update) {
        Artifact original = update.artifact();
        Map<String, Object> metadata = original.metadata() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(original.metadata());
        metadata.remove(com.openjiuwen.service.app.controller.a2a.A2aPartContent.TERMINAL_RESULT_METADATA);

        Object existingEvent = metadata.get(RemoteAgentCaller.AGENT_EVENT_METADATA);
        if (existingEvent == null) {
            metadata = outputMetadata(metadata, remoteAgentId(member), update.taskId());
        }
        String projectedArtifactId = existingEvent == null
                ? "remote:" + remoteAgentId(member) + ":" + update.taskId() + ":" + original.artifactId()
                : original.artifactId();
        Artifact projected = Artifact.builder(original).artifactId(projectedArtifactId).metadata(metadata).build();
        try {
            batch.observer.onNext(new QueryChunk(QueryChunk.TYPE_REMOTE_AGENT_OUTPUT,
                    new TaskArtifactUpdateEvent(update.taskId(), projected, update.contextId(), update.append(),
                            update.lastChunk(), update.metadata())));
        } catch (IllegalStateException ex) {
            if (state.failBatch(batch)) {
                batch.completion.completeExceptionally(ex);
            }
        }
    }

    private static String remoteAgentId(Member member) {
        return member.agentName.isBlank() ? member.toolName : member.agentName;
    }

    private static Map<String, Object> outputMetadata(Map<String, Object> metadata, String agentId, String taskId) {
        Map<String, Object> result = new LinkedHashMap<>(metadata);
        result.put(RemoteAgentCaller.AGENT_EVENT_METADATA,
                Map.of("type", "output", "source", agentRef(agentId, taskId)));
        return result;
    }

    private static Map<String, Object> delegationMetadata(String toolCallId, String sourceAgentId,
            String sourceTaskId, String targetAgentId, String targetTaskId) {
        return Map.of(RemoteAgentCaller.AGENT_EVENT_METADATA,
                Map.of("type", "delegation", "toolCallId", toolCallId,
                        "source", agentRef(sourceAgentId, sourceTaskId),
                        "target", agentRef(targetAgentId, targetTaskId)));
    }

    private static Map<String, Object> statusMetadata(String agentId, String taskId, TaskState state) {
        return Map.of(RemoteAgentCaller.AGENT_EVENT_METADATA,
                Map.of("type", "status", "source", agentRef(agentId, taskId), "state", normalizeState(state)));
    }

    private static Map<String, Object> agentRef(String agentId, String taskId) {
        return Map.of("agentId", agentId, "taskId", taskId);
    }

    private static String normalizeState(TaskState state) {
        return state.name().replaceFirst("^TASK_STATE_", "").toLowerCase(Locale.ROOT);
    }

    private void startNextQueuedAfterReleasedSlot() {
        Dispatch dispatch = state.dispatchAfterSlotRelease();
        for (PendingInvocation candidate : dispatch.expired()) {
            logMemberState(candidate.batch(), candidate.member());
            finishBatchIfSettled(candidate.batch());
        }
        PendingInvocation next = dispatch.next();
        if (next != null) {
            logMemberState(next.batch(), next.member());
            start(next);
        }
    }

    private static void logMemberState(RemoteInvocationBatch batch, Member member) {
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

    boolean claimCoreResume(ServeRequest request, String batchId) {
        String parentTaskId = parentTaskId(request);
        return state.claimCoreResume(parentTaskId, batchId);
    }

    private void releaseCoreResumeClaim(String parentTaskId, String batchId) {
        if (batchId == null || batchId.isBlank()) {
            return;
        }
        state.releaseCoreResumeClaim(parentTaskId, batchId);
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

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Optional<String> optionalNonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String remoteContextId(RemoteInvocationBatch batch, Member member) {
        String parentContextId = batch.request.getConversationId();
        if (parentContextId == null || parentContextId.isBlank()) {
            parentContextId = batch.parentTaskId;
        }
        if (batch.members.size() <= 1) {
            return parentContextId;
        }
        return parentContextId + "_" + batch.batchId + "_" + member.toolCallId;
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
}
