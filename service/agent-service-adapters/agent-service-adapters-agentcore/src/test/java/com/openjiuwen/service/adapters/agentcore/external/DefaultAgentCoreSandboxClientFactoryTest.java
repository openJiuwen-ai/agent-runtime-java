/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.sysop.config.ContainerScope;
import com.openjiuwen.core.sysop.config.SandboxGatewayConfig;
import com.openjiuwen.core.sysop.sandbox.SandboxClient;
import com.openjiuwen.core.sysop.sandbox.SandboxEndpoint;
import com.openjiuwen.core.sysop.sandbox.SandboxRegistry;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * Tests sandbox client factory mapping and validation behavior.
 *
 * @since 2026-06-24
 */
class DefaultAgentCoreSandboxClientFactoryTest {
    @Test
    void mapsValidSandboxServerConfigToVendorCoreGatewayConfig() {
        AgentCoreExternalProperties.SandboxServer server = new AgentCoreExternalProperties.SandboxServer();
        server.setServerId("default");
        server.setServiceUrl("http://localhost:18090");
        server.setSandboxType("jiuwenbox");
        server.setLauncherType("pre_deploy");
        server.setOnStop("keep");
        server.setRootPath("/tmp/sandbox");
        server.setIsolationKey("session-1");
        server.setIsolationPrefix("tenant-a");
        server.setContainerScope(ContainerScope.CUSTOM);
        server.setExtraParams(Map.of("sandbox_id", "sbx-1"));
        server.setTimeoutMs(4500);
        server.setIdleTtlSeconds(60);
        AgentCoreExternalProperties properties = properties();
        properties.getSandbox().setServers(java.util.List.of(server));

        SandboxGatewayConfig config = new DefaultAgentCoreSandboxClientFactory(properties).configFor("default");

        assertThat(config.getGatewayUrl()).isEqualTo("http://localhost:18090");
        assertThat(config.getTimeoutSeconds()).isEqualTo(5);
        assertThat(config.getParams()).containsEntry("root_path", "/tmp/sandbox");
        assertThat(config.getLauncherConfig().getLauncherType()).isEqualTo("pre_deploy");
        assertThat(config.getLauncherConfig().getGatewayUrl()).isEqualTo("http://localhost:18090");
        assertThat(config.getLauncherConfig().getBaseUrl()).isEqualTo("http://localhost:18090");
        assertThat(config.getLauncherConfig().getSandboxType()).isEqualTo("jiuwenbox");
        assertThat(config.getLauncherConfig().getOnStop()).isEqualTo("keep");
        assertThat(config.getLauncherConfig().getIdleTtlSeconds()).isEqualTo(60);
        assertThat(config.getLauncherConfig().getExtraParams()).containsEntry("sandbox_id", "sbx-1");
        assertThat(config.getIsolation().getCustomId()).isEqualTo("session-1");
        assertThat(config.getIsolation().getPrefix()).isEqualTo("tenant-a");
        assertThat(config.getIsolation().getContainerScope()).isEqualTo(ContainerScope.CUSTOM);
    }

    @Test
    void createReturnsVendorCoreSandboxClient() {
        DefaultAgentCoreSandboxClientFactory factory = new DefaultAgentCoreSandboxClientFactory(properties());

        SandboxClient client = factory.create();

        assertThat(client).isInstanceOf(SandboxClient.class);
        assertThat(client).isInstanceOf(DecoratingSandboxClient.class);
        assertThat(client.getConfig().getGatewayUrl()).isEqualTo("http://localhost:18090");
    }

    @Test
    void doesNotRegisterRuntimeSandboxProviders() {
        AgentCoreExternalProperties properties = properties();
        String sandboxType = "custom_" + java.util.UUID.randomUUID().toString().replace("-", "");
        properties.getSandbox().getServers().get(0).setSandboxType(sandboxType);
        SandboxRegistry.registerProvider(sandboxType, "fs", ExistingFsProvider.class);

        try {
            new DefaultAgentCoreSandboxClientFactory(properties);

            assertThat(SandboxRegistry.getProviderClass(sandboxType, "fs")).isEqualTo(ExistingFsProvider.class);
            assertThat(SandboxRegistry.getProviderClass(sandboxType, "shell")).isNull();
            assertThat(SandboxRegistry.getProviderClass(sandboxType, "code")).isNull();
        } finally {
            SandboxRegistry.unregisterProvider(sandboxType, "fs");
            SandboxRegistry.unregisterProvider(sandboxType, "shell");
            SandboxRegistry.unregisterProvider(sandboxType, "code");
        }
    }

    @Test
    void failsWhenRequestedSandboxServerDoesNotExist() {
        DefaultAgentCoreSandboxClientFactory factory = new DefaultAgentCoreSandboxClientFactory(properties());

        assertThatThrownBy(() -> factory.configFor("missing")).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown sandbox server");
    }

    @Test
    void rejectsEnabledSandboxWithInvalidUrl() {
        AgentCoreExternalProperties properties = properties();
        properties.getSandbox().getServers().get(0).setServiceUrl("file:///tmp/sandbox");

        assertThatThrownBy(() -> new DefaultAgentCoreSandboxClientFactory(properties)).isInstanceOf(
            IllegalArgumentException.class).hasMessageContaining("service-url");
    }

    private AgentCoreExternalProperties properties() {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getSandbox().setEnabled(true);
        properties.getSandbox().setTimeoutMs(2500);
        AgentCoreExternalProperties.SandboxServer server = new AgentCoreExternalProperties.SandboxServer();
        server.setServerId("default");
        server.setServiceUrl("http://localhost:18090");
        server.setSandboxType("jiuwenbox");
        properties.getSandbox().setServers(java.util.List.of(server));
        return properties;
    }

    static final class ExistingFsProvider {
        ExistingFsProvider(SandboxEndpoint endpoint, SandboxGatewayConfig config) {
        }
    }
}
