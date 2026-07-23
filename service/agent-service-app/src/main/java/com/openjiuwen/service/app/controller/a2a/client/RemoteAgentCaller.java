/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.spec.spi.QueryStreamObserver;

/**
 * SPI for invoking a remote agent identified by {@code agentId}.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link A2ARemoteAgentClient} — baseline, A2A SDK via
 *       {@code Client.builder(card).withTransport(JSONRPCTransport.class, config)};
 *       ignores {@link RemoteAgentCall#responseContent()}; no {@code messages} append.</li>
 *   <li>{@code A2AGatewayRemoteAgentCaller} (deployment module) —
 *       {@code gatewayBaseUrl + "/" + agentId + jsonRpcPath} routing; consumes
 *       {@code responseContent} to append an assistant message to {@code messages}.</li>
 *   <li>{@code InProcessRemoteAgentCaller} (deployment module, dev scope) —
 *       direct {@code AgentHandler} bean lookup; consumes {@code responseContent}.</li>
 * </ul>
 *
 * <p>The caller of this SPI is {@code A2AEnabledServeOrchestrator} (runtime core);
 * it is NOT the Versatile Adapter.
 *
 * @since 0.1.0
 */
public interface RemoteAgentCaller {
    /**
     * Invoke the remote agent, branching on {@link RemoteAgentCall#streaming()}
     * to preserve the legacy {@code callStreaming} / {@code callSync} split.
     *
     * <p><b>Streaming mode</b> ({@link RemoteAgentCall#streaming()} is
     * {@code true}) — implementations MUST:
     * <ul>
     *   <li>use a streaming SDK client</li>
     *   <li>forward each {@code QueryChunk("chunk", ...)} to {@code observer}
     *       (the final answer is tapped, not consumed)</li>
     *   <li>map remote INPUT_REQUIRED to {@code QueryChunk("interrupt", ...)}
     *       on the observer, then {@code observer.onComplete()}</li>
     *   <li>map remote failure / timeout to {@code observer.onError(...)} with
     *       a {@link RemoteAgentException} (no subsequent {@code onComplete})</li>
     * </ul>
     *
     * <p><b>Sync mode</b> ({@link RemoteAgentCall#streaming()} is
     * {@code false}) — implementations MUST:
     * <ul>
     *   <li>use a non-streaming SDK client</li>
     *   <li>emit a final {@code QueryChunk("chunk", answer)} whose payload is
     *       the raw {@code task.artifacts()} text, then {@code observer.onComplete()}</li>
     *   <li>throw {@link RemoteInputRequiredException} on INPUT_REQUIRED (no
     *       observer notification)</li>
     *   <li>throw {@link RemoteAgentException} on failure / timeout (no
     *       observer notification)</li>
     * </ul>
     *
     * <p>The orchestrator's capturing observer extracts the answer from the
     * final chunk in both modes (envelope-extracted text for streaming, raw
     * artifact text for sync).
     *
     * @param call     the remote call coordinates
     * @param observer the observer for streaming chunks; never {@code null}
     */
    void call(RemoteAgentCall call, QueryStreamObserver observer);

    /**
     * Whether this caller supports routing to {@code agentId}.
     *
     * <p>Used when multiple Caller beans coexist (e.g. Default + A2AGateway). The
     * auto-configuration selects a single primary Caller by {@code @Primary} or
     * {@code @Order}; this method is a fallback for runtime selection in mixed deployments.
     *
     * @param agentId the target agent id
     * @return true if this caller can route to {@code agentId}
     */
    boolean supported(String agentId);
}
