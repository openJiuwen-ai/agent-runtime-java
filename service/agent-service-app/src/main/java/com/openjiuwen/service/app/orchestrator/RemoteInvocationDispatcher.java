/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.orchestrator.RemoteInvocationBatch.MemberState;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationCoordinatorState.Dispatch;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationCoordinatorState.PendingInvocation;
import com.openjiuwen.service.app.orchestrator.RemoteInvocationCoordinatorState.Submission;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Process-wide remote invocation permits and FIFO queue. Local coordinators
 * retain batches and persistence; queued work retains its original coordinator.
 * No callback runs under this dispatcher's lock.
 *
 * @since 0.1.2
 */
public final class RemoteInvocationDispatcher {
    private final int maxConcurrency;

    private final int maxQueueSize;

    private final Duration queueTimeout;

    private final Deque<PendingInvocation> queue = new ArrayDeque<>();

    private final Set<PendingInvocation> running = new HashSet<>();

    private final Set<RemoteInvocationBatchCoordinator> stoppedOwners =
            Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * Creates one execution and queue budget shared by registered targets.
     *
     * @param maxConcurrency maximum running remote calls
     * @param maxQueueSize maximum waiting calls
     * @param queueTimeout maximum queue wait
     */
    public RemoteInvocationDispatcher(int maxConcurrency, int maxQueueSize, Duration queueTimeout) {
        if (maxConcurrency < 1 || maxQueueSize < 0 || queueTimeout == null || queueTimeout.isNegative()) {
            throw new IllegalArgumentException("Invalid remote invocation dispatch limits");
        }
        this.maxConcurrency = maxConcurrency;
        this.maxQueueSize = maxQueueSize;
        this.queueTimeout = queueTimeout;
    }

    Duration queueTimeout() {
        return queueTimeout;
    }

    synchronized Submission submit(PendingInvocation invocation) {
        if (invocation.batch().isResolved) {
            return Submission.IGNORED;
        }
        if (stoppedOwners.contains(invocation.owner())) {
            invocation.member().fail(MemberState.FAILED, "REMOTE_CANCELLED", "Hosted agent is stopping");
            return Submission.OVERLOADED;
        }
        if (running.size() < maxConcurrency) {
            reserve(invocation);
            return Submission.START;
        }
        if (queue.size() < maxQueueSize) {
            invocation.member().state = MemberState.QUEUED;
            invocation.member().queuedAt = Instant.now();
            queue.addLast(invocation);
            return Submission.QUEUED;
        }
        invocation.member().fail(MemberState.FAILED, "REMOTE_OVERLOADED", "Remote invocation queue is full");
        return Submission.OVERLOADED;
    }

    synchronized boolean expireQueued(PendingInvocation invocation) {
        if (invocation.batch().isResolved || !queue.remove(invocation)) {
            return false;
        }
        failExpired(invocation);
        return true;
    }

    synchronized boolean prepareStart(PendingInvocation invocation) {
        boolean canStart = !invocation.batch().isResolved && !stoppedOwners.contains(invocation.owner())
                && running.contains(invocation)
                && invocation.member().state == MemberState.RUNNING;
        if (!canStart) {
            running.remove(invocation);
            if (!invocation.batch().isResolved) {
                invocation.member().fail(MemberState.FAILED, "REMOTE_CANCELLED", "Hosted agent is stopping");
            }
        }
        return canStart;
    }

    synchronized Dispatch finish(PendingInvocation invocation) {
        if (!running.remove(invocation)) {
            return new Dispatch(List.of(), null);
        }
        return nextDispatch();
    }

    synchronized Dispatch nextDispatch() {
        List<PendingInvocation> expired = new ArrayList<>();
        while (running.size() < maxConcurrency && !queue.isEmpty()) {
            PendingInvocation candidate = queue.removeFirst();
            if (candidate.batch().isResolved) {
                continue;
            }
            if (Duration.between(candidate.member().queuedAt, Instant.now()).compareTo(queueTimeout) > 0) {
                failExpired(candidate);
                expired.add(candidate);
            } else {
                reserve(candidate);
                return new Dispatch(expired, candidate);
            }
        }
        return new Dispatch(expired, null);
    }

    synchronized void removeBatch(RemoteInvocationBatch batch) {
        queue.removeIf(invocation -> invocation.batch() == batch);
    }

    synchronized List<PendingInvocation> stopOwner(RemoteInvocationBatchCoordinator owner) {
        stoppedOwners.add(owner);
        List<PendingInvocation> removed = new ArrayList<>();
        queue.removeIf(invocation -> {
            if (invocation.owner() != owner) {
                return false;
            }
            invocation.member().fail(MemberState.FAILED, "REMOTE_CANCELLED", "Hosted agent is stopping");
            removed.add(invocation);
            return true;
        });
        // Running calls retain their leases until the original caller completes.
        return removed;
    }

    private void reserve(PendingInvocation invocation) {
        running.add(invocation);
        invocation.member().startedAt = Instant.now();
        invocation.member().state = MemberState.RUNNING;
    }

    private static void failExpired(PendingInvocation invocation) {
        invocation.member().fail(MemberState.FAILED, "REMOTE_OVERLOADED", "Remote invocation queue wait timed out");
    }
}
