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

    static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private final Map<String, RemoteAgentEntry> entries = new ConcurrentHashMap<>();

    public void register(String name, AgentCard card) {
        register(name, card, DEFAULT_TIMEOUT_SECONDS);
    }

    public List<RemoteAgentEntry> getAll() {
        return List.copyOf(entries.values());
    }

    public Optional<RemoteAgentEntry> get(String name) {
        return Optional.ofNullable(entries.get(name));
    }

    public String resolveUrl(String name) {
        var entry = entries.get(name);
        if (entry == null) return "";
        var ifaces = entry.card().supportedInterfaces();
        if (ifaces == null || ifaces.isEmpty()) return "";
        return ifaces.get(0).url();
    }

    public record RemoteAgentEntry(String name, AgentCard card, int timeoutSeconds) {}

    public void register(String name, AgentCard card, int timeoutSeconds) {
        entries.put(name, new RemoteAgentEntry(name, card, timeoutSeconds));
    }
}
