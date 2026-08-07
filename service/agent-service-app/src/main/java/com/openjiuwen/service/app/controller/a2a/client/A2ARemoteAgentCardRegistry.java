/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.spec.AgentCard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe in-memory registry of discovered remote A2A AgentCards.
 *
 * @since 0.1.0
 */
public class A2ARemoteAgentCardRegistry {
    private static final Logger log = LoggerFactory.getLogger(A2ARemoteAgentCardRegistry.class);

    /**
     * Default timeout in seconds for remote agent calls.
     */
    static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, RemoteAgentEntry> entries = new ConcurrentHashMap<>();
    private final ReentrantLock updateLock = new ReentrantLock();

    private long version;

    /**
     * Creates a registry without event publication.
     *
     * <p>This constructor preserves direct, non-Spring usage. Runtime auto-configuration
     * supplies an {@link ApplicationEventPublisher}.</p>
     */
    public A2ARemoteAgentCardRegistry() {
        this(event -> {
        });
    }

    /**
     * Creates a registry that publishes complete catalog snapshots after updates.
     *
     * @param eventPublisher the Spring application event publisher
     */
    public A2ARemoteAgentCardRegistry(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

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
        return snapshot().entries();
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
     * Returns the current complete remote-agent catalog.
     *
     * @return an immutable, name-sorted catalog snapshot
     */
    public RemoteAgentCatalogSnapshot snapshot() {
        updateLock.lock();
        try {
            return createSnapshot();
        } finally {
            updateLock.unlock();
        }
    }

    /**
     * A registered remote agent entry holding the card and timeout configuration.
     */
    public record RemoteAgentEntry(String name, AgentCard card, int timeoutSeconds, boolean isStreaming) {
    }

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
        RemoteAgentCatalogSnapshot updatedSnapshot;
        updateLock.lock();
        try {
            entries.put(name, new RemoteAgentEntry(name, card, timeoutSeconds, isStreaming));
            version++;
            updatedSnapshot = createSnapshot();
        } finally {
            updateLock.unlock();
        }
        publishCatalogChanged(updatedSnapshot);
    }

    private RemoteAgentCatalogSnapshot createSnapshot() {
        List<RemoteAgentEntry> sortedEntries = entries.values().stream()
                .sorted((left, right) -> left.name().compareTo(right.name())).toList();
        return new RemoteAgentCatalogSnapshot(version, sortedEntries);
    }

    private void publishCatalogChanged(RemoteAgentCatalogSnapshot updatedSnapshot) {
        try {
            eventPublisher.publishEvent(new RemoteAgentCatalogChangedEvent(updatedSnapshot));
        } catch (RuntimeException exception) {
            log.error("Failed to publish remote Agent Card catalog event, version={}", updatedSnapshot.version(),
                    exception);
            throw exception;
        }
    }
}
