/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.A2AProperties.RemoteAgentProperties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

/**
 * Unit tests for {@link A2AAgentCardDiscovery} configuration validation.
 *
 * @since 0.1.0
 */
class A2AAgentCardDiscoveryTest {
    private A2AAgentCardDiscovery discovery;

    @AfterEach
    void tearDown() {
        if (discovery != null) {
            discovery.shutdown();
        }
    }

    @Test
    void missingRemoteAgentUrlFailsWithConfigurationKey() {
        A2AProperties properties = propertiesWith(remoteAgent("weather-agent", null));
        discovery = new A2AAgentCardDiscovery(properties, new A2ARemoteAgentCardRegistry());

        assertThatThrownBy(discovery::discoverAll).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid A2A remote agent configuration: "
                        + "openjiuwen.service.a2a.remote-agents[0].url must not be null or blank");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void missingRemoteAgentNameFailsWithConfigurationKey(String name) {
        A2AProperties properties = propertiesWith(remoteAgent(name, "http://127.0.0.1:12345"));
        discovery = new A2AAgentCardDiscovery(properties, new A2ARemoteAgentCardRegistry());

        assertThatThrownBy(discovery::discoverAll).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid A2A remote agent configuration: "
                        + "openjiuwen.service.a2a.remote-agents[0].name must not be null or blank");
    }

    @Test
    void missingNameIsReportedBeforeMissingUrl() {
        A2AProperties properties = propertiesWith(remoteAgent(null, null));
        discovery = new A2AAgentCardDiscovery(properties, new A2ARemoteAgentCardRegistry());

        assertThatThrownBy(discovery::discoverAll).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid A2A remote agent configuration: "
                        + "openjiuwen.service.a2a.remote-agents[0].name must not be null or blank");
    }

    @Test
    void allRemoteAgentsAreValidatedBeforeDiscoveryStarts() {
        A2AProperties properties = propertiesWith(remoteAgent("weather-agent", "not a URI"),
                remoteAgent("travel-agent", "   "));
        discovery = new A2AAgentCardDiscovery(properties, new A2ARemoteAgentCardRegistry());

        assertThatThrownBy(discovery::discoverAll).isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid A2A remote agent configuration: "
                        + "openjiuwen.service.a2a.remote-agents[1].url must not be null or blank");
    }

    @Test
    void fetchCardRejectsMissingBaseUrl() {
        discovery = new A2AAgentCardDiscovery(new A2AProperties(), new A2ARemoteAgentCardRegistry());

        assertThatThrownBy(() -> discovery.fetchCardInternal(null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("baseUrl must not be null or blank");
    }

    private static A2AProperties propertiesWith(RemoteAgentProperties... remoteAgents) {
        A2AProperties properties = new A2AProperties();
        properties.setRemoteAgents(List.of(remoteAgents));
        return properties;
    }

    private static RemoteAgentProperties remoteAgent(String name, String url) {
        RemoteAgentProperties remote = new RemoteAgentProperties();
        remote.setName(name);
        remote.setUrl(url);
        return remote;
    }
}
