/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;

import java.util.concurrent.CompletableFuture;

/**
 * SPI for invoking a remote agent identified by {@code agentName}.
 *
 * <p>The primary consumer is {@code RemoteInvocationBatchCoordinator}, which
 * fans out parallel {@link #callOutcome} invocations and aggregates their
 * {@link RemoteCallOutcome}s into a single batch resolution. The coordinator
 * depends solely on this SPI, so deployments that swap the caller bean
 * (e.g. gateway routing) automatically affect batch execution.
 *
 * @since 0.1.0
 */
public interface RemoteAgentCaller {
    /** Metadata key used for remote agent delegation, status, and output events. */
    String AGENT_EVENT_METADATA = "agentEvent";

    /**
     * Invokes the remote agent asynchronously and returns a structured outcome.
     *
     * <p>Implementations MUST:
     * <ul>
     *   <li>resolve the remote agent entry by {@link RemoteCall#agentName()}</li>
     *   <li>deliver complete streaming Artifact and status updates to
     *       {@code eventObserver} without rebuilding their business Parts</li>
     *   <li>complete the returned future with a {@link RemoteCallOutcome} on
     *       terminal remote state (COMPLETED / FAILED / INPUT_REQUIRED / etc.),
     *       or complete it exceptionally on transport failure / timeout</li>
     * </ul>
     *
     * <p>The future MUST be cancelable — the coordinator cancels it when the
     * caller cancels the batch.
     *
     * @param call the remote call coordinates
     * @param eventObserver observer for complete remote A2A events
     * @return a future completing with the structured remote outcome
     */
    CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, EventObserver eventObserver);

    /** Receives complete A2A status and Artifact updates. */
    interface EventObserver {
        /**
         * Receives a remote task status update.
         *
         * @param event complete A2A status update
         */
        void onStatus(TaskStatusUpdateEvent event);

        /**
         * Receives a remote task Artifact update.
         *
         * @param event complete A2A Artifact update
         */
        void onArtifact(TaskArtifactUpdateEvent event);
    }
}
