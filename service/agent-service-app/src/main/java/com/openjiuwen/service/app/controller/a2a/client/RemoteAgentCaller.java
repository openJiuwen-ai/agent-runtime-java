/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * SPI for invoking a remote agent identified by {@code agentName}.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link A2ARemoteAgentClient} — baseline, A2A SDK via
 *       {@code Client.builder(card).withTransport(JSONRPCTransport.class, config)};
 *       taps the remote answer artifact and routes intermediate chunks to the
 *       observer.</li>
 *   <li>{@code A2AGatewayRemoteAgentCaller} (deployment module) —
 *       {@code gatewayBaseUrl + "/" + agentName + jsonRpcPath} routing; consumes
 *       {@code responseContent} to append an assistant message to {@code messages}.</li>
 * </ul>
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
    /**
     * Invokes the remote agent asynchronously and returns a structured outcome.
     *
     * <p>Implementations MUST:
     * <ul>
     *   <li>resolve the remote agent entry by {@link RemoteCall#agentName()}</li>
     *   <li>forward intermediate chunks (streaming artifacts, progress events)
     *       to {@code streamObserver} when non-null</li>
     *   <li>notify {@code remoteTaskIdObserver} of the remote task id as soon as
     *       it is known, so the batch coordinator can persist it for resume</li>
     *   <li>complete the returned future with a {@link RemoteCallOutcome} on
     *       terminal remote state (COMPLETED / FAILED / INPUT_REQUIRED / etc.),
     *       or complete it exceptionally on transport failure / timeout</li>
     * </ul>
     *
     * <p>The future MUST be cancelable — the coordinator cancels it when the
     * caller cancels the batch.
     *
     * @param call                 the remote call coordinates
     * @param streamObserver       observer for intermediate streaming chunks; may be {@code null}
     * @param remoteTaskIdObserver observer for the remote task id; may be {@code null}
     * @return a future completing with the structured remote outcome
     */
    CompletableFuture<RemoteCallOutcome> callOutcome(RemoteCall call, QueryStreamObserver streamObserver,
            Consumer<String> remoteTaskIdObserver);

    /**
     * Whether this caller supports routing to {@code agentName}.
     *
     * <p>Used when multiple Caller beans coexist (e.g. Default + A2AGateway). The
     * auto-configuration selects a single primary Caller by {@code @Primary} or
     * {@code @Order}; this method is a fallback for runtime selection in mixed deployments.
     *
     * @param agentName the target agent name
     * @return true if this caller can route to {@code agentName}
     */
    boolean supported(String agentName);
}
