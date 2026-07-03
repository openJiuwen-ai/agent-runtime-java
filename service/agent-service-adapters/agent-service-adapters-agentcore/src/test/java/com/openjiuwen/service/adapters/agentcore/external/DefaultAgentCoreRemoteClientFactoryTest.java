/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.external;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.runner.drunner.remoteclient.ProtocolEnum;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClient;
import com.openjiuwen.core.runner.drunner.remoteclient.RemoteClientConfig;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Tests remote client factory mapping and validation behavior.
 *
 * @since 2026-06-24
 */
class DefaultAgentCoreRemoteClientFactoryTest {
    @Test
    void mapsConfiguredA2aClientToVendorCoreRemoteClientConfig() {
        AgentCoreExternalProperties properties = properties();
        AgentCoreExternalProperties.RemoteClientEndpoint client = properties.getRemote().getClients().get(0);
        client.setName("Configured A2A Agent");
        client.setTimeoutMs(1500);

        RemoteClientConfig config = new DefaultAgentCoreRemoteClientFactory(properties,
                new DefaultAgentCoreRemoteClientDecoratorFactory()).configFor("remote-a2a");

        assertThat(config.getId()).isEqualTo("remote-a2a");
        assertThat(config.getName()).isEqualTo("Configured A2A Agent");
        assertThat(config.getProtocol()).isEqualTo(ProtocolEnum.A2A);
        assertThat(config.getUrl()).isEqualTo("http://localhost:18082");
        assertThat(properties.policyFor(config).getTimeoutMs()).isEqualTo(1500);
    }

    @Test
    void createReturnsDecoratedA2aRemoteClientFromConfiguredClient() {
        try (CoreProviderRegistrySnapshot ignored = CoreProviderRegistrySnapshot.capture()) {
            AgentCoreRemoteClientFactory factory = new DefaultAgentCoreRemoteClientFactory(properties(),
                    new DefaultAgentCoreRemoteClientDecoratorFactory());

            RemoteClient client = factory.create("remote-a2a");

            assertThat(client).isInstanceOf(DecoratingRemoteClient.class);
        }
    }

    @Test
    void createWithoutClientIdUsesFirstConfiguredRemoteClient() {
        RemoteClientConfig config = new DefaultAgentCoreRemoteClientFactory(properties(),
                new DefaultAgentCoreRemoteClientDecoratorFactory()).configFor(null);

        assertThat(config.getId()).isEqualTo("remote-a2a");
    }

    @Test
    void failsWhenRequestedRemoteClientDoesNotExist() {
        AgentCoreRemoteClientFactory factory = new DefaultAgentCoreRemoteClientFactory(properties(),
                new DefaultAgentCoreRemoteClientDecoratorFactory());

        assertThatThrownBy(() -> factory.configFor("missing")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown remote client");
    }

    @Test
    void rejectsRemoteClientWithInvalidUrl() {
        AgentCoreExternalProperties properties = properties();
        properties.getRemote().getClients().get(0).setUrl("file:///tmp/a2a");

        assertThatThrownBy(() -> new DefaultAgentCoreRemoteClientFactory(properties,
                new DefaultAgentCoreRemoteClientDecoratorFactory())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("url");
    }

    @Test
    void rejectsRemoteClientWithUnsupportedProtocol() {
        AgentCoreExternalProperties properties = properties();
        properties.getRemote().getClients().get(0).setProtocol("MQ");

        assertThatThrownBy(() -> new DefaultAgentCoreRemoteClientFactory(properties,
                new DefaultAgentCoreRemoteClientDecoratorFactory())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("protocol");
    }

    private AgentCoreExternalProperties properties() {
        AgentCoreExternalProperties properties = new AgentCoreExternalProperties();
        properties.getRemote().setTimeoutMs(3000);
        AgentCoreExternalProperties.RemoteClientEndpoint client = new AgentCoreExternalProperties.RemoteClientEndpoint();
        client.setId("remote-a2a");
        client.setName("Remote A2A");
        client.setProtocol("A2A");
        client.setUrl("http://localhost:18082");
        properties.getRemote().setClients(List.of(client));
        return properties;
    }
}
