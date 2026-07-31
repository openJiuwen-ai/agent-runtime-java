/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.Member;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.MemberState;

import org.a2aproject.sdk.spec.Task;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns all lock-protected coordinator state and dispatcher transitions. */
final class RemoteInvocationCoordinatorState {
    private static final int MAX_EARLY_CALLBACKS = 256;

    private final int maxConcurrency;

    private final int maxQueueSize;

    private final Duration queueTimeout;

    private final Deque<PendingInvocation> queue = new ArrayDeque<>();

    private final Map<String, RemoteInvocationBatch> activeByParent = new LinkedHashMap<>();

    private final Map<String, String> coreResumeClaims = new LinkedHashMap<>();

    private final Map<String, Task> earlyCallbacksByRemoteTaskId = new LinkedHashMap<>();

    private int activeCount;

    RemoteInvocationCoordinatorState(int maxConcurrency, int maxQueueSize, Duration queueTimeout) {
        this.maxConcurrency = maxConcurrency;
        this.maxQueueSize = maxQueueSize;
        this.queueTimeout = queueTimeout;
    }

    synchronized boolean hasActiveBatch(String parentTaskId) {
        return activeByParent.containsKey(parentTaskId);
    }

    synchronized boolean registerBatch(RemoteInvocationBatch batch) {
        if (activeByParent.containsKey(batch.parentTaskId)) {
            return false;
        }
        activeByParent.put(batch.parentTaskId, batch);
        return true;
    }

    synchronized void removeBatch(RemoteInvocationBatch batch) {
        activeByParent.remove(batch.parentTaskId, batch);
    }

    synchronized void rememberEarlyCallback(Task task) {
        earlyCallbacksByRemoteTaskId.put(task.id(), task);
        while (earlyCallbacksByRemoteTaskId.size() > MAX_EARLY_CALLBACKS) {
            String eldest = earlyCallbacksByRemoteTaskId.keySet().iterator().next();
            earlyCallbacksByRemoteTaskId.remove(eldest);
        }
    }

    synchronized List<Task> takeEarlyCallbacks(RemoteInvocationBatch batch) {
        List<Task> callbacks = new ArrayList<>();
        for (Member member : batch.members) {
            if (member.remoteTaskId == null || member.remoteTaskId.isBlank()) {
                continue;
            }
            Task callback = earlyCallbacksByRemoteTaskId.remove(member.remoteTaskId);
            if (callback != null) {
                callbacks.add(callback);
            }
        }
        return callbacks;
    }

    synchronized Submission submit(PendingInvocation invocation) {
        if (invocation.batch.isResolved) {
            return Submission.IGNORED;
        }
        if (activeCount < maxConcurrency) {
            activeCount++;
            invocation.member.state = MemberState.RUNNING;
            invocation.member.startedAt = Instant.now();
            return Submission.START;
        }
        if (queue.size() < maxQueueSize) {
            invocation.member.state = MemberState.QUEUED;
            invocation.member.queuedAt = Instant.now();
            queue.addLast(invocation);
            return Submission.QUEUED;
        }
        invocation.member.fail(MemberState.FAILED, "REMOTE_OVERLOADED", "Remote invocation queue is full");
        return Submission.OVERLOADED;
    }

    synchronized boolean expireQueued(PendingInvocation invocation) {
        if (invocation.batch.isResolved || invocation.member.state != MemberState.QUEUED || !queue.remove(invocation)) {
            return false;
        }
        invocation.member.fail(MemberState.FAILED, "REMOTE_OVERLOADED", "Remote invocation queue wait timed out");
        return true;
    }

    synchronized boolean prepareStart(PendingInvocation invocation) {
        boolean isStartAllowed = !invocation.batch.isResolved && invocation.member.state == MemberState.RUNNING;
        if (!isStartAllowed) {
            activeCount = Math.max(0, activeCount - 1);
        }
        return isStartAllowed;
    }

    synchronized void captureRemoteTaskId(RemoteInvocationBatch batch, Member member, String remoteTaskId) {
        if (!batch.isResolved && member.state == MemberState.RUNNING) {
            member.remoteTaskId = remoteTaskId;
        }
    }

    synchronized InvocationCompletion finishInvocation(PendingInvocation invocation, Runnable outcomeApplier) {
        boolean shouldApplyOutcome = !invocation.batch.isResolved;
        if (shouldApplyOutcome) {
            outcomeApplier.run();
        }
        activeCount = Math.max(0, activeCount - 1);
        Dispatch dispatch = nextDispatch();
        return new InvocationCompletion(shouldApplyOutcome, dispatch.expired, dispatch.next);
    }

    synchronized Dispatch dispatchAfterSlotRelease() {
        return nextDispatch();
    }

    private Dispatch nextDispatch() {
        List<PendingInvocation> expired = new ArrayList<>();
        PendingInvocation next = null;
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
        return new Dispatch(expired, next);
    }

    synchronized boolean isResolved(RemoteInvocationBatch batch) {
        return batch.isResolved;
    }

    synchronized boolean settle(RemoteInvocationBatch batch) {
        boolean isSettled = !batch.isResolved && batch.members.stream()
                .noneMatch(member -> member.state == MemberState.QUEUED || member.state == MemberState.RUNNING);
        if (isSettled) {
            batch.isResolved = true;
        }
        return isSettled;
    }

    synchronized boolean failBatch(RemoteInvocationBatch batch) {
        if (batch.isResolved) {
            return false;
        }
        batch.isResolved = true;
        activeByParent.remove(batch.parentTaskId, batch);
        queue.removeIf(invocation -> invocation.batch == batch);
        return true;
    }

    synchronized boolean claimCoreResume(String parentTaskId, String batchId) {
        if (coreResumeClaims.containsKey(parentTaskId)) {
            return false;
        }
        coreResumeClaims.put(parentTaskId, batchId);
        return true;
    }

    synchronized void releaseCoreResumeClaim(String parentTaskId, String batchId) {
        coreResumeClaims.remove(parentTaskId, batchId);
    }

    enum Submission {
        START, QUEUED, OVERLOADED, IGNORED
    }

    record PendingInvocation(RemoteInvocationBatch batch, Member member) {
    }

    record Dispatch(List<PendingInvocation> expired, PendingInvocation next) {
    }

    record InvocationCompletion(boolean isOutcomeApplied, List<PendingInvocation> expired, PendingInvocation next) {
    }
}
