/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;
import com.openjiuwen.service.spec.dto.ServeRequest;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Mutable state for one remote invocation batch. */
final class RemoteInvocationBatch {
    final String batchId;

    final String parentTaskId;

    final ServeRequest request;

    final SerialQueryStreamObserver observer;

    final List<Member> members;

    final boolean shouldResume;

    final CompletableFuture<RemoteInvocationBatchCoordinator.BatchResolution> completion = new CompletableFuture<>();

    boolean isResolved;

    RemoteInvocationBatch(String batchId, String parentTaskId, ServeRequest request, SerialQueryStreamObserver observer,
            List<Member> members, boolean shouldResume) {
        this.batchId = batchId;
        this.parentTaskId = parentTaskId;
        this.request = request;
        this.observer = observer;
        this.members = members;
        this.shouldResume = shouldResume;
    }

    enum MemberState {
        QUEUED, RUNNING, COMPLETED, INPUT_REQUIRED, FAILED, TIMED_OUT
    }

    /** Mutable state for one member of a batch. */
    static final class Member {
        final int index;

        final String toolCallId;

        final String toolName;

        final String agentName;

        String message;

        volatile MemberState state = MemberState.QUEUED;

        String remoteTaskId = "";

        Object result;

        String resultCategory;

        String inputPrompt;

        String errorMessage;

        AgentFailureDescriptor remoteFailure;

        Instant queuedAt = Instant.now();

        Instant startedAt;

        Instant completedAt;

        Member(int index, String toolCallId, String toolName, String agentName, String message) {
            this.index = index;
            this.toolCallId = toolCallId;
            this.toolName = toolName;
            this.agentName = agentName;
            this.message = message;
        }

        void fail(MemberState failedState, String category, String failureMessage) {
            resultCategory = category;
            errorMessage = failureMessage;
            remoteFailure = null;
            completedAt = Instant.now();
            state = failedState;
        }
    }
}
