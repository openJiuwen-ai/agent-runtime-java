/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes one immutable directory only after all handlers have started.
 * Readers never observe a partially populated target map.
 *
 * @since 0.1.2
 */
public final class HostedRuntimeCatalog {
    private final HostedAgentDefinitions definitions;

    private volatile Map<String, HostedAgentRuntime> published;

    private boolean wasPublished;

    private boolean isClosed;

    public HostedRuntimeCatalog(HostedAgentDefinitions definitions) {
        this.definitions = definitions;
    }

    synchronized void publish(List<HostedAgentRuntime> instances) {
        if (wasPublished || isClosed) {
            throw new IllegalStateException("Hosted runtime catalog cannot be republished");
        }
        if (instances.size() != definitions.entries().size()) {
            throw new IllegalStateException("Hosted runtime catalog is incomplete");
        }
        Map<String, HostedAgentRuntime> snapshot = new LinkedHashMap<>();
        for (int i = 0; i < instances.size(); i++) {
            var entry = definitions.entries().get(i);
            var instance = instances.get(i);
            if (!entry.agentId().equals(instance.agentId()) || entry.handler() != instance.handler()) {
                throw new IllegalStateException("Hosted assembly does not match registered handler identity");
            }
            snapshot.put(instance.agentId(), instance);
        }
        wasPublished = true;
        published = Collections.unmodifiableMap(snapshot);
    }

    synchronized void close() {
        isClosed = true;
        published = null;
    }

    public HostedAgentRuntime resolve(String agentId) {
        Map<String, HostedAgentRuntime> snapshot = snapshot();
        HostedAgentRuntime target = snapshot.get(agentId == null ? definitions.defaultAgentId() : agentId);
        if (target == null) {
            throw new HostedIngressResolver.SelectionException(404, "HOSTED_AGENT_NOT_FOUND", "Unknown agent");
        }
        return target;
    }

    public HostedAgentRuntime defaultRuntime() {
        return resolve(null);
    }

    public List<HostedAgentRuntime> instances() {
        return List.copyOf(snapshot().values());
    }

    public String defaultAgentId() {
        snapshot();
        return definitions.defaultAgentId();
    }

    private Map<String, HostedAgentRuntime> snapshot() {
        Map<String, HostedAgentRuntime> snapshot = published;
        if (snapshot == null) {
            throw new HostedIngressResolver.SelectionException(503, "AGENT_NOT_READY", "Agent is not ready");
        }
        return snapshot;
    }
}
