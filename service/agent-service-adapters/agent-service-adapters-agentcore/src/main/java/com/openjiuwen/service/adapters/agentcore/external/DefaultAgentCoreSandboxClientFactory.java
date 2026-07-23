/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.config.SandboxIsolationConfig;
import com.openjiuwen.core.sysop.config.SandboxLauncherConfig;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.credential.PassthroughCredentialDecryptor;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport;
import com.openjiuwen.service.adapters.common.security.ExternalOutboundSecuritySupport.PreparedOutboundSecurity;
import com.openjiuwen.service.spec.security.ExternalTargetRef;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.time.Duration;

/**
 * Factory that creates Core sandbox clients from external sandbox
 * configuration.
 *
 * @since 2026-06-24
 */
public class DefaultAgentCoreSandboxClientFactory implements AgentCoreSandboxClientFactory {
    private final AgentCoreExternalProperties properties;

    private final ExternalOutboundSecuritySupport outboundSecuritySupport;

    public DefaultAgentCoreSandboxClientFactory(AgentCoreExternalProperties properties) {
        this(properties, ExternalOutboundSecuritySupport.createDefault(new PassthroughCredentialDecryptor()));
    }

    public DefaultAgentCoreSandboxClientFactory(AgentCoreExternalProperties properties,
        ExternalOutboundSecuritySupport outboundSecuritySupport) {
        this.properties = properties != null ? properties : new AgentCoreExternalProperties();
        this.outboundSecuritySupport = outboundSecuritySupport != null ? outboundSecuritySupport
            : ExternalOutboundSecuritySupport.createDefault(new PassthroughCredentialDecryptor());
        this.properties.getSandbox().validate();
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

    private SandboxGatewayConfig toCoreConfig(AgentCoreExternalProperties.SandboxServer server,
        AgentCoreExternalProperties.SandboxPolicy policy) {
        Map<String, Object> params = new LinkedHashMap<>(server.getParams());
        if (server.getRootPath() != null && !server.getRootPath().isBlank()) {
            params.putIfAbsent("root_path", server.getRootPath());
        }
        PreparedOutboundSecurity security = prepareSandboxSecurity(server, policy);
        security.injectParams(params);

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

    private PreparedOutboundSecurity prepareSandboxSecurity(AgentCoreExternalProperties.SandboxServer server,
        AgentCoreExternalProperties.SandboxPolicy policy) {
        int timeoutMs = server.getTimeoutMs() != null ? server.getTimeoutMs() : policy.getTimeoutMs();
        ExternalTargetRef target = new ExternalTargetRef("Sandbox", server.getServerId(), server.getServiceUrl(),
            CredentialSceneType.SANDBOX_AUTH_TOKEN);
        return outboundSecuritySupport.prepare(target, server.getTls(), server.getAuth(),
            Duration.ofMillis(timeoutMs));
    }

    private static int toTimeoutSeconds(int timeoutMs) {
        int timeoutSeconds = BigDecimal.valueOf(timeoutMs)
            .divide(BigDecimal.valueOf(1000), 0, RoundingMode.CEILING)
            .intValue();
        return Math.max(1, timeoutSeconds);
    }

    private static String defaultText(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static Optional<String> nonBlankText(String value) {
        return value != null && !value.isBlank() ? Optional.of(value) : Optional.empty();
    }
}
