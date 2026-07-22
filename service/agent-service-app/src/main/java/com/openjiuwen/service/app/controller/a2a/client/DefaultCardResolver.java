/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

/**
 * Baseline {@link RemoteAgentCardResolver} that reads cached {@link org.a2aproject.sdk.spec.AgentCard}s
 * from {@link A2ARemoteAgentCardRegistry} (populated by {@code A2AAgentCardDiscovery}
 * at startup).
 *
 * <p>Logic-equivalent to the legacy {@code A2AAgentCardDiscovery.fetchCard(baseUrl)}
 * URL construction ({@code baseUrl + "/.well-known/agent-card.json"}) and
 * {@code A2ARemoteAgentCardRegistry.resolveUrl(name)} (first interface URL).
 *
 * @since 0.1.0
 */
public class DefaultCardResolver implements RemoteAgentCardResolver {
    private static final String CARD_URL_SUFFIX = "/.well-known/agent-card.json";

    private final A2ARemoteAgentCardRegistry registry;

    /**
     * Constructs the default card resolver.
     *
     * @param registry the remote agent card registry
     */
    public DefaultCardResolver(A2ARemoteAgentCardRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String resolveCardUrl(String agentId) {
        String jsonRpcUrl = resolveJsonRpcUrl(agentId);
        if (jsonRpcUrl == null || jsonRpcUrl.isBlank()) {
            return "";
        }
        int lastSlash = jsonRpcUrl.lastIndexOf('/');
        if (lastSlash <= 8) {
            return jsonRpcUrl.replaceAll("/$", "") + CARD_URL_SUFFIX;
        }
        return jsonRpcUrl.substring(0, lastSlash) + CARD_URL_SUFFIX;
    }

    @Override
    public String resolveJsonRpcUrl(String agentId) {
        if (agentId == null) {
            return "";
        }
        return registry.resolveUrl(agentId);
    }

    @Override
    public boolean supported(String agentId) {
        return agentId != null && registry.get(agentId).isPresent();
    }
}
