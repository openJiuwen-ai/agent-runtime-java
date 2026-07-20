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
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.credential.PassthroughCredentialDecryptor;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport.PreparedOutboundSecurity;
import com.openjiuwen.service.spec.security.ExternalTargetRef;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private final ExternalOutboundSecuritySupport outboundSecuritySupport;

    public DefaultAgentCoreRemoteClientFactory(AgentCoreExternalProperties properties,
        AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory) {
        this(properties, remoteDecoratorFactory, List.of());
    }

    public DefaultAgentCoreRemoteClientFactory(AgentCoreExternalProperties properties,
        AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory,
        List<RemoteClientProvider> customRemoteClientProviders) {
        this(properties, remoteDecoratorFactory, customRemoteClientProviders,
            ExternalOutboundSecuritySupport.createDefault(new PassthroughCredentialDecryptor()));
    }

    public DefaultAgentCoreRemoteClientFactory(AgentCoreExternalProperties properties,
        AgentCoreRemoteClientDecoratorFactory remoteDecoratorFactory,
        List<RemoteClientProvider> customRemoteClientProviders,
        ExternalOutboundSecuritySupport outboundSecuritySupport) {
        this.properties = properties != null ? properties : new AgentCoreExternalProperties();
        this.remoteDecoratorFactory = remoteDecoratorFactory != null
            ? remoteDecoratorFactory
            : new DefaultAgentCoreRemoteClientDecoratorFactory();
        this.customRemoteClientProviders = customRemoteClientProviders != null ? List.copyOf(
            customRemoteClientProviders) : Collections.emptyList();
        this.outboundSecuritySupport = outboundSecuritySupport != null ? outboundSecuritySupport
            : ExternalOutboundSecuritySupport.createDefault(new PassthroughCredentialDecryptor());
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
        Map<String, Object> kwargs = new LinkedHashMap<>();
        PreparedOutboundSecurity security = prepareRemoteSecurity(client);
        security.injectRemoteKwargs(kwargs);
        return RemoteClientConfig.builder()
            .id(client.getId())
            .name(defaultText(client.getName(), client.getId()))
            .protocol(toProtocol(client.getProtocol()))
            .url(client.getUrl())
            .kwargs(kwargs)
            .build();
    }

    private PreparedOutboundSecurity prepareRemoteSecurity(AgentCoreExternalProperties.RemoteClientEndpoint client) {
        int timeoutMs = client.getTimeoutMs() != null ? client.getTimeoutMs() : properties.getRemote().getTimeoutMs();
        ExternalTargetRef target = new ExternalTargetRef("Remote", client.getId(), client.getUrl(),
            CredentialSceneType.REMOTE_AUTH_TOKEN);
        return outboundSecuritySupport.prepare(target, client.getTls(), client.getAuth(),
            Duration.ofMillis(timeoutMs));
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
