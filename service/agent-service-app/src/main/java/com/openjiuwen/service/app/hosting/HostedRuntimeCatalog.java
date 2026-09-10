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

    private boolean hasPublished;

    private boolean isClosed;

    public HostedRuntimeCatalog(HostedAgentDefinitions definitions) {
        this.definitions = definitions;
    }

    synchronized void publish(List<HostedAgentRuntime> instances) {
        if (hasPublished || isClosed) {
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
        hasPublished = true;
        published = Collections.unmodifiableMap(snapshot);
    }

    synchronized void close() {
        isClosed = true;
        published = null;
    }

    /**
     * Resolves a published target, using the default registration when the ID is absent.
     *
     * @param agentId registration ID, or null for the default
     * @return selected runtime
     * @throws HostedIngressResolver.SelectionException if unavailable or unknown
     */
    public HostedAgentRuntime resolve(String agentId) {
        Map<String, HostedAgentRuntime> snapshot = snapshot();
        HostedAgentRuntime target = snapshot.get(agentId == null ? definitions.defaultAgentId() : agentId);
        if (target == null) {
            throw new HostedIngressResolver.SelectionException(404, "HOSTED_AGENT_NOT_FOUND", "Unknown agent");
        }
        return target;
    }

    /**
     * Returns the published default target.
     *
     * @return default runtime
     */
    public HostedAgentRuntime defaultRuntime() {
        return resolve(null);
    }

    /**
     * Returns the immutable published targets in registration order.
     *
     * @return registered runtimes
     */
    public List<HostedAgentRuntime> instances() {
        return List.copyOf(snapshot().values());
    }

    /**
     * Returns the default registration ID after readiness validation.
     *
     * @return default registration ID
     */
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
