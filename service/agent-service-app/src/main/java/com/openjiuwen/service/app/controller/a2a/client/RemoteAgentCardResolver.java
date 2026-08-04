/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

/**
 * SPI for resolving a remote agent's A2A URLs by {@code agentId}.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link A2AAgentCardDiscovery} — looks up the cached {@link AgentCard} from
 *       {@link A2ARemoteAgentCardRegistry} (populated by {@code A2AAgentCardDiscovery}
 *       at startup); returns the first interface's URL.</li>
 *   <li>{@code A2AGatewayCardResolver} (deployment module) —
 *       {@code gatewayBaseUrl + "/" + agentId + "/.well-known/agent-card.json"}.</li>
 * </ul>
 *
 * @since 0.1.0
 */
public interface RemoteAgentCardResolver {
    /**
     * Resolve the Agent Card URL for {@code agentId} (used to fetch the card).
     *
     * @param agentId the target agent id
     * @return the card URL, or empty string if unknown
     */
    String resolveCardUrl(String agentId);

    /**
     * Resolve the JSON-RPC URL for {@code agentId} (used to send messages).
     *
     * @param agentId the target agent id
     * @return the JSON-RPC URL, or empty string if unknown
     */
    String resolveJsonRpcUrl(String agentId);

    /**
     * Whether this resolver can produce URLs for {@code agentId}.
     *
     * @param agentId the target agent id
     * @return true if the agentId is resolvable
     */
    boolean supported(String agentId);
}
