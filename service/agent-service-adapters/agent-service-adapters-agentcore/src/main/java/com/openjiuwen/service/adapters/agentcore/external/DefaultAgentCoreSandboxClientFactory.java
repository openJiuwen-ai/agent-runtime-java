/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.config.SandboxIsolationConfig;
import com.openjiuwen.core.sysop.config.SandboxLauncherConfig;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Factory that creates Core sandbox clients from external sandbox configuration.
 *
 * @since 2026-06-24
 */
public class DefaultAgentCoreSandboxClientFactory implements AgentCoreSandboxClientFactory {
    private final AgentCoreExternalProperties properties;

    public DefaultAgentCoreSandboxClientFactory(AgentCoreExternalProperties properties) {
        this.properties = properties != null ? properties : new AgentCoreExternalProperties();
        this.properties.getSandbox().validate();
        HttpSandboxProviderRegistrar.register(this.properties.getSandbox());
    }

    @Override
    public SandboxClient create() {
        return create(null);
    }

    @Override
    public SandboxClient create(String serverId) {
        AgentCoreExternalProperties.SandboxPolicy policy = properties.getSandbox();
        Optional<AgentCoreExternalProperties.SandboxServer> server = policy.findServer(serverId);
        SandboxClient delegate = new SandboxClient(configFor(serverId));
        String resolvedServerId = server.map(AgentCoreExternalProperties.SandboxServer::getServerId).orElse(serverId);
        return new DecoratingSandboxClient(resolvedServerId, delegate, properties.policyFor(server));
    }

    @Override
    public SandboxGatewayConfig configFor(String serverId) {
        AgentCoreExternalProperties.SandboxPolicy policy = properties.getSandbox();
        policy.validate();
        Optional<AgentCoreExternalProperties.SandboxServer> server = policy.findServer(serverId);
        AgentCoreExternalProperties.SandboxServer resolvedServer = server.orElseThrow(
                () -> new IllegalArgumentException("Unknown sandbox server: " + serverId));
        AgentCoreExternalProperties.SandboxPolicy effectivePolicy = properties.policyFor(server);
        return toCoreConfig(resolvedServer, effectivePolicy);
    }

    private SandboxGatewayConfig toCoreConfig(
            AgentCoreExternalProperties.SandboxServer server,
            AgentCoreExternalProperties.SandboxPolicy policy) {
        Map<String, Object> params = new LinkedHashMap<>(server.getParams());
        if (server.getRootPath() != null && !server.getRootPath().isBlank()) {
            params.putIfAbsent("root_path", server.getRootPath());
        }

        SandboxLauncherConfig launcherConfig = SandboxLauncherConfig.builder()
                .launcherType(defaultText(server.getLauncherType(), "pre_deploy"))
                .gatewayUrl(server.getServiceUrl())
                .baseUrl(server.getServiceUrl())
                .sandboxType(server.getSandboxType())
                .onStop(defaultText(server.getOnStop(), "delete"))
                .idleTtlSeconds(server.getIdleTtlSeconds())
                .extraParams(new LinkedHashMap<>(server.getExtraParams()))
                .build();

        SandboxIsolationConfig isolationConfig = SandboxIsolationConfig.builder()
                .customId(nonBlankText(server.getIsolationKey()).orElse(null))
                .prefix(nonBlankText(server.getIsolationPrefix()).orElse(null))
                .containerScope(server.getContainerScope())
                .build();

        return SandboxGatewayConfig.builder()
                .gatewayUrl(server.getServiceUrl())
                .timeoutSeconds(toTimeoutSeconds(policy.getTimeoutMs()))
                .launcherConfig(launcherConfig)
                .isolation(isolationConfig)
                .params(params)
                .build();
    }

    private static int toTimeoutSeconds(int timeoutMs) {
        return Math.max(1, (int) Math.ceil(timeoutMs / 1000.0d));
    }

    private static String defaultText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static Optional<String> nonBlankText(String value) {
        return value != null && !value.isBlank() ? Optional.of(value) : Optional.empty();
    }
}
