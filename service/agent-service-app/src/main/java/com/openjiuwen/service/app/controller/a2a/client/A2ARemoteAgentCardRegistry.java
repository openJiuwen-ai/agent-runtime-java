/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.spec.AgentCard;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory registry of discovered remote A2A AgentCards.
 *
 * @since 0.1.0
 */
@Component
public class A2ARemoteAgentCardRegistry {
    /**
     * Default timeout in seconds for remote agent calls.
     */
    static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final Map<String, RemoteAgentEntry> entries = new ConcurrentHashMap<>();

    /**
     * Registers a remote agent card using the default timeout.
     *
     * @param name the agent name
     * @param card the agent card
     */
    public void register(String name, AgentCard card) {
        register(name, card, DEFAULT_TIMEOUT_SECONDS, false);
    }

    /**
     * Returns all registered remote agent entries.
     *
     * @return an unmodifiable copy of all entries
     */
    public List<RemoteAgentEntry> getAll() {
        return List.copyOf(entries.values());
    }

    /**
     * Looks up a remote agent entry by name.
     *
     * @param name the agent name
     * @return the entry, or {@link Optional#empty()} if not found
     */
    public Optional<RemoteAgentEntry> get(String name) {
        return Optional.ofNullable(entries.get(name));
    }

    /**
     * Resolves the JSON-RPC URL for a registered remote agent.
     *
     * @param name the agent name
     * @return the JSON-RPC URL, or empty string if not found
     */
    public String resolveUrl(String name) {
        var entry = entries.get(name);
        if (entry == null) {
            return "";
        }
        var ifaces = entry.card().supportedInterfaces();
        if (ifaces == null || ifaces.isEmpty()) {
            return "";
        }
        return ifaces.get(0).url();
    }

    /**
     * A registered remote agent entry holding the card and timeout configuration.
     */
    public record RemoteAgentEntry(String name, AgentCard card, int timeoutSeconds, boolean isStreaming) {}

    /**
     * Registers a remote agent card with a specific timeout.
     *
     * @param name the agent name
     * @param card the agent card
     * @param timeoutSeconds the call timeout in seconds
     */
    public void register(String name, AgentCard card, int timeoutSeconds) {
        register(name, card, timeoutSeconds, false);
    }

    /**
     * Registers a remote agent card with timeout and invocation mode.
     *
     * @param name the agent name
     * @param card the agent card
     * @param timeoutSeconds the call timeout in seconds
     * @param isStreaming whether Runtime should prefer a streaming remote invocation
     */
    public void register(String name, AgentCard card, int timeoutSeconds, boolean isStreaming) {
        entries.put(name, new RemoteAgentEntry(name, card, timeoutSeconds, isStreaming));
    }
}
