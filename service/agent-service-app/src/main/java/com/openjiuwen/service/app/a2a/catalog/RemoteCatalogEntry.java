/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import com.openjiuwen.service.adapters.common.security.ExternalTlsConfig;

import org.a2aproject.sdk.spec.AgentCard;

import java.util.List;

/**
 * One catalog entry: the card fact (what to call) plus the instance candidates
 * (where to call). The candidate list only trusts the instance plane; address
 * declarations inside the card are never used for instance-level routing.
 *
 * <p>Static entries carry their single derived candidate (the first JSON-RPC
 * interface of the fetched card) plus the outbound TLS channel configuration;
 * dynamic entries carry the instance candidates resolved from the registry,
 * and never carry TLS (outbound TLS is a static-entry concept; the transport
 * security of dynamically discovered targets relies on the deployment plane).
 * An empty candidate list is the legal upgrade-window state.</p>
 *
 * @param agentId target agent identifier
 * @param card the agent card fact
 * @param timeoutSeconds remote call timeout in seconds
 * @param isStreaming whether Runtime should prefer streaming invocation
 * @param instances instance candidates; never null, empty means no live candidate
 * @param tls target-specific outbound TLS configuration, or null for defaults
 * @since 0.1.4
 */
public record RemoteCatalogEntry(String agentId, AgentCard card, int timeoutSeconds, boolean isStreaming,
        List<RemoteInstanceCandidate> instances, ExternalTlsConfig tls) {
    /**
     * Creates an immutable entry with a defensively copied candidate list.
     */
    public RemoteCatalogEntry {
        instances = instances == null ? List.of() : List.copyOf(instances);
    }
}
