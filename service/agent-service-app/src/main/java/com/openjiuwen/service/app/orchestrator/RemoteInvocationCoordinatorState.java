/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.Member;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.MemberState;

import org.a2aproject.sdk.spec.Task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns all lock-protected coordinator state and dispatcher transitions. */
final class RemoteInvocationCoordinatorState {
    private static final int MAX_EARLY_CALLBACKS = 256;

    private final RemoteInvocationDispatcher dispatcher;

    private final Map<String, RemoteInvocationBatch> activeByParent = new LinkedHashMap<>();

    private final Map<String, String> coreResumeClaims = new LinkedHashMap<>();

    private final Map<String, Task> earlyCallbacksByRemoteTaskId = new LinkedHashMap<>();

    RemoteInvocationCoordinatorState(RemoteInvocationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
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
        return dispatcher.submit(invocation);
    }

    synchronized boolean expireQueued(PendingInvocation invocation) {
        return dispatcher.expireQueued(invocation);
    }

    synchronized boolean prepareStart(PendingInvocation invocation) {
        return dispatcher.prepareStart(invocation);
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
        Dispatch dispatch = dispatcher.finish(invocation);
        return new InvocationCompletion(shouldApplyOutcome, dispatch.expired, dispatch.next);
    }

    synchronized Dispatch dispatchAfterSlotRelease() {
        return dispatcher.nextDispatch();
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
        dispatcher.removeBatch(batch);
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

    record PendingInvocation(RemoteInvocationBatch batch, Member member, RemoteInvocationBatchCoordinator owner) {
    }

    record Dispatch(List<PendingInvocation> expired, PendingInvocation next) {
    }

    record InvocationCompletion(boolean isOutcomeApplied, List<PendingInvocation> expired, PendingInvocation next) {
    }
}
