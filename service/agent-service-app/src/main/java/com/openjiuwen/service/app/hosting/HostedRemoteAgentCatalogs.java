/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentCatalogChangedEvent;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentEntry;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.A2AProperties.RemoteAgentProperties;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Binds each hosted instance to its effective remote directory. Local names mask
 * global entries before discovery, including while a local endpoint is unavailable.
 */
public final class HostedRemoteAgentCatalogs {
    private final Map<String, A2ARemoteAgentCardRegistry> catalogs = new LinkedHashMap<>();
    private final Map<String, List<RemoteAgentProperties>> localConfigurations = new LinkedHashMap<>();
    private final Map<String, Set<String>> localNames = new LinkedHashMap<>();
    private long globalVersion;

    /**
     * Prepares live directories without performing network discovery.
     *
     * @param definitions registered local handlers
     * @param properties global and instance remote configuration
     * @param global existing process-wide remote registry
     * @param publisher publisher for instance-scoped catalog changes
     */
    public HostedRemoteAgentCatalogs(HostedAgentDefinitions definitions, A2AProperties properties,
            A2ARemoteAgentCardRegistry global, ApplicationEventPublisher publisher) {
        var snapshot = global.snapshot();
        globalVersion = snapshot.version();
        for (var entry : definitions.entries()) {
            var configuration = properties.getAgents().get(entry.agentId());
            List<RemoteAgentProperties> locals = configuration == null
                    ? List.of() : List.copyOf(configuration.getRemoteAgents());
            if (locals.isEmpty()) {
                catalogs.put(entry.agentId(), global);
                continue;
            }
            Set<String> names = locals.stream().map(RemoteAgentProperties::getName).collect(Collectors.toSet());
            if (names.size() != locals.size()) {
                throw new IllegalArgumentException("Duplicate remote agent names for hosted agent: " + entry.agentId());
            }
            localNames.put(entry.agentId(), names);
            localConfigurations.put(entry.agentId(), locals);
            var catalog = new A2ARemoteAgentCardRegistry(event -> {
                var changed = (RemoteAgentCatalogChangedEvent) event;
                publisher.publishEvent(new RemoteAgentCatalogChangedEvent(changed.snapshot(), entry.agentId()));
            });
            catalogs.put(entry.agentId(), catalog);
            copyInherited(entry.agentId(), snapshot.entries());
        }
    }

    /**
     * Returns the live directory bound to a registered instance.
     *
     * @param agentId local registration ID
     * @return effective remote directory
     */
    public A2ARemoteAgentCardRegistry catalog(String agentId) {
        var catalog = catalogs.get(agentId);
        if (catalog == null) {
            throw new IllegalArgumentException("Unknown hosted agent: " + agentId);
        }
        return catalog;
    }

    /**
     * Returns local discovery inputs; inherited targets are discovered once globally.
     *
     * @return instance IDs mapped to their local entries
     */
    public Map<String, List<RemoteAgentProperties>> localConfigurations() {
        return Map.copyOf(localConfigurations);
    }

    /**
     * Indicates whether a separate remote binding is needed.
     *
     * @param agentId local registration ID
     * @return true when the instance declares local remote entries
     */
    public boolean hasLocalConfiguration(String agentId) {
        return localConfigurations.containsKey(agentId);
    }

    /**
     * Propagates global discovery/retry updates without overwriting local names.
     *
     * @param event global or instance directory update
     */
    @EventListener
    public synchronized void onGlobalCatalogChanged(RemoteAgentCatalogChangedEvent event) {
        if (event.agentId() != null || event.snapshot().version() <= globalVersion) {
            return;
        }
        globalVersion = event.snapshot().version();
        for (String agentId : localConfigurations.keySet()) {
            copyInherited(agentId, event.snapshot().entries());
        }
    }

    private void copyInherited(String agentId, List<RemoteAgentEntry> entries) {
        var catalog = catalogs.get(agentId);
        for (var entry : entries) {
            if (!localNames.get(agentId).contains(entry.name())
                    && catalog.get(entry.name()).filter(entry::equals).isEmpty()) {
                catalog.register(entry.name(), entry.card(), entry.timeoutSeconds(), entry.isStreaming(), entry.tls());
            }
        }
    }
}
