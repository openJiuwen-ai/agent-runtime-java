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
 *   <li>{@link DefaultRemoteAgentCaller} — baseline, A2A SDK via
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
     * Invoke the remote agent, forwarding chunks to {@code observer}.
     *
     * <p>Implementations MUST:
     * <ul>
     *   <li>resolve the remote URL/card via {@link RemoteAgentCardResolver}</li>
     *   <li>build the A2A SDK message from {@link RemoteAgentCall#serveRequest()}</li>
     *   <li>decide whether to consume {@link RemoteAgentCall#responseContent()} (Default: ignore)</li>
     *   <li>forward each {@code QueryChunk("chunk", ...)} to {@code observer}</li>
     *   <li>emit a final {@code QueryChunk("chunk", answer-envelope)} whose payload
     *       contains the remote's final business text (so the orchestrator's
     *       capturing observer can extract it for sync {@code query()} mode)</li>
     *   <li>map remote INPUT_REQUIRED to {@code QueryChunk("interrupt", ...)} on the observer</li>
     *   <li>map remote failure / timeout to {@code observer.onError(...)} with a
     *       {@code RemoteAgentException} or {@code RemoteInputRequiredException} cause</li>
     * </ul>
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
