/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.runner.drunner.remoteclient.ProtocolEnum;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientFactory;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientProvider;
import com.openjiuwen.extensions.a2a.A2ARemoteClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Default remote client factory backed by Core RemoteClientFactory.
 *
 * @since 2026-06-24
 */
public class DefaultAgentCoreRemoteClientFactory implements AgentCoreRemoteClientFactory {
    private static final Logger log = LoggerFactory.getLogger(DefaultAgentCoreRemoteClientFactory.class);

    private final AgentCoreExternalProperties properties;
    private final AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory;
    private final List<RemoteClientProvider> customRemoteClientProviders;

    public DefaultAgentCoreRemoteClientFactory(AgentCoreExternalProperties properties,
            AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory) {
        this(properties, remoteDecoratorFactory, List.of());
    }

    public DefaultAgentCoreRemoteClientFactory(AgentCoreExternalProperties properties,
            AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory,
            List<RemoteClientProvider> customRemoteClientProviders) {
        this.properties = properties != null ? properties : new AgentCoreExternalProperties();
        this.remoteDecoratorFactory = remoteDecoratorFactory != null
                ? remoteDecoratorFactory
                : new DefaultAgentCoreRemoteClientDecoratorFactory();
        this.customRemoteClientProviders = customRemoteClientProviders != null
                ? List.copyOf(customRemoteClientProviders)
                : Collections.emptyList();
        this.properties.getRemote().validateClients();
    }

    @Override
    public RemoteClient create() {
        return create(null);
    }

    @Override
    public RemoteClient create(String clientId) {
        registerRemoteClientProviders();
        return RemoteClientFactory.create(configFor(clientId));
    }

    @Override
    public RemoteClientConfig configFor(String clientId) {
        AgentCoreExternalProperties.RemotePolicy policy = properties.getRemote();
        policy.validateClients();
        AgentCoreExternalProperties.RemoteClientEndpoint client = policy.findClient(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown remote client: " + clientId));
        return RemoteClientConfig.builder().id(client.getId()).name(defaultText(client.getName(), client.getId()))
                .protocol(toProtocol(client.getProtocol())).url(client.getUrl()).build();
    }

    private void registerRemoteClientProviders() {
        Set<String> customProtocols = registerCustomRemoteClientProviders();
        if (customProtocols.contains("A2A")) {
            return;
        }
        RemoteClientFactory.register("A2A", new RemoteClientProvider() {
            @Override
            public String typeName() {
                return "A2A";
            }

            @Override
            public RemoteClient create(RemoteClientConfig config) {
                RemoteClient delegate = new A2ARemoteClient(config);
                return remoteDecoratorFactory.decorate(config, delegate, properties.policyFor(config));
            }
        });
    }

    private Set<String> registerCustomRemoteClientProviders() {
        Set<String> protocols = new LinkedHashSet<>();
        for (RemoteClientProvider provider : customRemoteClientProviders) {
            if (provider == null || provider.typeName() == null || provider.typeName().isBlank()) {
                continue;
            }
            String protocol = provider.typeName().toUpperCase(Locale.ROOT);
            RemoteClientFactory.register(protocol, provider);
            protocols.add(protocol);
            log.info("Registered custom remote client provider, protocol={}", protocol);
        }
        return protocols;
    }

    private ProtocolEnum toProtocol(String protocol) {
        if (protocol == null || protocol.isBlank()) {
            return ProtocolEnum.A2A;
        }
        return ProtocolEnum.valueOf(protocol.toUpperCase(Locale.ROOT));
    }

    private String defaultText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
