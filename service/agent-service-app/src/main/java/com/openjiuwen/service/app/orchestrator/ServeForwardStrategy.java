/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.Optional;

/**
 * SPI for deciding whether the orchestrator should forward a local agent's
 * result to a remote agent via {@link com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller}.
 *
 * <p>This strategy owns the <b>forward decision</b> (when to forward and what
 * {@link RemoteAgentCall} to build). The orchestrator owns the <b>forward
 * execution</b> (calling the remote, capturing the result, managing shadow
 * tasks for resume, and honouring the observer contract). Splitting the two
 * keeps the runtime-core orchestrator free of Versatile-specific envelope
 * detection while still providing a generic, reusable forward path.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link NoopServeForwardStrategy} (runtime core, default) — never
 *       forwards; preserves legacy behaviour when no deployment-module
 *       strategy is on the classpath.</li>
 *   <li>{@code ThreeFieldForwardStrategy} (deployment module
 *       {@code versatile-intent-boot}) — detects a Versatile intent-workflow
 *       three-field answer envelope ({@code agent_id} + {@code response_content}
 *       + optional {@code intent_id}) and builds a {@link RemoteAgentCall}.</li>
 * </ul>
 *
 * <p>The strategy MUST be side-effect free and fast: in streaming mode
 * {@link #interceptStreamEnvelope} is invoked synchronously on every chunk
 * inside the observer's {@code onNext}, so blocking I/O would stall the chunk
 * stream.
 *
 * @since 0.1.0
 */
public interface ServeForwardStrategy {
    /**
     * Sync mode: invoked after {@code agentHandler.query()} returns. Inspect
     * the local response and decide whether to forward to a remote agent.
     *
     * @param localResponse the local agent's response; never {@code null}
     * @param request       the original serve request; never {@code null}
     * @return the {@link RemoteAgentCall} to execute, or {@link Optional#empty()}
     *         to return the local response unchanged
     */
    Optional<RemoteAgentCall> evaluateForward(QueryResponse localResponse, ServeRequest request);

    /**
     * Streaming mode: invoked for every chunk the local agent emits. Inspect
     * the chunk and decide whether it is a forward-triggering envelope.
     *
     * <p>When this returns a non-empty {@link RemoteAgentCall}, the orchestrator
     * stops forwarding subsequent chunks to the client observer, executes the
     * remote call, and streams the remote's chunks to the client instead. The
     * triggering chunk is NOT forwarded to the client.
     *
     * @param chunk   the chunk emitted by the local agent; never {@code null}
     * @param request the original serve request; never {@code null}
     * @return the {@link RemoteAgentCall} to execute, or {@link Optional#empty()}
     *         to keep forwarding the chunk to the client observer
     */
    Optional<RemoteAgentCall> interceptStreamEnvelope(QueryChunk chunk, ServeRequest request);
}
