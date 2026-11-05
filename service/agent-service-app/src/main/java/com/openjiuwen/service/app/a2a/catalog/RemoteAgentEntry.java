/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import com.openjiuwen.service.adapters.common.security.ExternalTlsConfig;

import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.TransportProtocol;
import org.a2aproject.sdk.util.Utils;

import java.util.List;

/**
 * Immutable remote A2A Agent registration entry.
 *
 * <p>The instance candidate list is the routing view. When no explicit
 * candidates are carried (static-compat view), the single candidate is
 * derived from the card's first JSON-RPC interface, with its URL resolved
 * exactly like the baseline endpoint resolution. Unified-catalog entries
 * carry the instance candidates explicitly, where an empty list is the legal
 * upgrade-window state (the entry stays in the catalog but is not callable).</p>
 *
 * @param name remote Agent name
 * @param card discovered Agent Card
 * @param timeoutSeconds remote call timeout in seconds
 * @param isStreaming whether Runtime should prefer streaming invocation
 * @param tls target-specific outbound TLS configuration, or null for defaults
 * @param instances instance candidates as carried by the catalog, or null to
 *        derive the static single-candidate view from the card; read through
 *        {@link #candidates()}, never null there; an explicit empty list means
 *        no live candidate
 * @since 0.1.1
 */
public record RemoteAgentEntry(String name, AgentCard card, int timeoutSeconds, boolean isStreaming,
        ExternalTlsConfig tls, List<RemoteInstanceCandidate> instances) {

    /**
     * Creates an immutable entry, keeping a null candidate list as the marker
     * of the derived static-compat view.
     */
    public RemoteAgentEntry {
        if (instances != null) {
            instances = List.copyOf(instances);
        }
    }

    /**
     * Creates an entry without target-specific TLS settings or explicit
     * candidates.
     *
     * @param name remote agent name
     * @param card discovered agent card
     * @param timeoutSeconds remote call timeout in seconds
     * @param isStreaming whether Runtime should prefer streaming invocation
     */
    public RemoteAgentEntry(String name, AgentCard card, int timeoutSeconds, boolean isStreaming) {
        this(name, card, timeoutSeconds, isStreaming, null, null);
    }

    /**
     * Creates an entry without explicit instance candidates.
     *
     * @param name remote agent name
     * @param card discovered agent card
     * @param timeoutSeconds remote call timeout in seconds
     * @param isStreaming whether Runtime should prefer streaming invocation
     * @param tls target-specific outbound TLS configuration, or null for defaults
     */
    public RemoteAgentEntry(String name, AgentCard card, int timeoutSeconds, boolean isStreaming,
            ExternalTlsConfig tls) {
        this(name, card, timeoutSeconds, isStreaming, tls, null);
    }

    /**
     * Returns the instance candidates of this entry, deriving the single
     * static-compat candidate from the card when none were carried.
     *
     * @return the candidate list, never null; empty means no live candidate
     */
    public List<RemoteInstanceCandidate> candidates() {
        return instances != null ? instances : deriveCandidates(card);
    }

    /**
     * Returns whether the candidate view is derived from the card instead of
     * carried by the catalog.
     *
     * @return {@code true} when no explicit candidates were carried
     */
    public boolean hasDerivedCandidates() {
        return instances == null;
    }

    private static List<RemoteInstanceCandidate> deriveCandidates(AgentCard card) {
        if (card == null || card.supportedInterfaces() == null || card.supportedInterfaces().isEmpty()) {
            return List.of();
        }
        return card.supportedInterfaces().stream()
                .filter(iface -> TransportProtocol.JSONRPC.asString().equals(iface.protocolBinding()))
                .findFirst()
                .map(iface -> List.of(new RemoteInstanceCandidate("", Utils.buildBaseUrl(iface, null), 0)))
                .orElseGet(List::of);
    }
}
