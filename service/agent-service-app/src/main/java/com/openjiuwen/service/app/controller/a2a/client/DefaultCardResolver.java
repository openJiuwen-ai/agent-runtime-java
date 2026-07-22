/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.spec.paths.A2AServicePaths;

import java.net.URI;

/**
 * Baseline {@link RemoteAgentCardResolver} that reads cached
 * {@link org.a2aproject.sdk.spec.AgentCard}s from {@link A2ARemoteAgentCardRegistry}
 * (populated by {@code A2AAgentCardDiscovery} at startup).
 *
 * <p>{@link #resolveJsonRpcUrl} delegates to {@link A2ARemoteAgentCardRegistry#resolveUrl}
 * (the card's first interface URL). {@link #resolveCardUrl} derives the agent-card
 * fetch URL by stripping the jsonRpcUrl's last path segment and appending
 * {@link A2AServicePaths#WELL_KNOWN_AGENT_CARD}. This is correct when the card's
 * interface URL shares the same origin and path prefix as the configured base URL
 * (the common A2A deployment). For cross-origin or deeply nested interface URLs,
 * the deployment module should override this resolver.
 *
 * @since 0.1.0
 */
public class DefaultCardResolver implements RemoteAgentCardResolver {
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
        try {
            URI uri = URI.create(jsonRpcUrl);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null || host.isBlank()) {
                return "";
            }
            StringBuilder base = new StringBuilder().append(scheme).append("://").append(host);
            int port = uri.getPort();
            if (port > 0) {
                base.append(':').append(port);
            }
            return base.append(A2AServicePaths.WELL_KNOWN_AGENT_CARD).toString();
        } catch (IllegalArgumentException ex) {
            return "";
        }
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
