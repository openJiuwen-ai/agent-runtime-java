/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.ServiceProperties;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Verifies hosted Card overrides, defaults and endpoint construction.
 *
 * @since 0.1.2
 */
class HostedAgentCardFactoryTest {
    private final HostedAgentDefinitions definitions = HostedAgentDefinitions.builder()
            .add("a", mock(AgentHandler.class)).add("b", mock(AgentHandler.class)).defaultAgent("b").build();

    @Test
    void freezesContentAndKeepsDefaultAndNamedAddressViewsDistinct() {
        A2AProperties properties = new A2AProperties();
        properties.setPublicUrl("https://example.com/proxy/");
        properties.setAgentDescription("global business content must not leak");
        properties.setProviderOrganization("common provider");
        var overrides = new A2AProperties.HostedCardProperties();
        overrides.setAgentName("Display B");
        overrides.setAgentDescription("B only");
        List<String> modes = new ArrayList<>(List.of("application/json"));
        overrides.setDefaultOutputModes(modes);
        properties.getAgents().put("b", overrides);
        var factory = new HostedAgentCardFactory(definitions, properties, new ServiceProperties());
        overrides.setAgentName("mutated");
        modes.clear();
        var root = factory.card("b", "http://localhost:123", true);
        var named = factory.card("b", "http://localhost:456", false);
        assertThat(root.name()).isEqualTo("Display B");
        assertThat(root.description()).isEqualTo("B only");
        assertThat(root.defaultOutputModes()).containsExactly("application/json");
        assertThat(root.supportedInterfaces().get(0).url()).isEqualTo("https://example.com/proxy/a2a");
        assertThat(named.supportedInterfaces().get(0).url()).isEqualTo("https://example.com/proxy/a2a/agents/b");
        var a = factory.card("a", "http://ignored", false);
        assertThat(a.name()).isEqualTo("a");
        assertThat(a.description()).isEmpty();
        assertThat(a.skills()).isEmpty();
        assertThat(a.provider().organization()).isEqualTo("common provider");
        assertThat(factory.callbackUrl("b")).isEqualTo("https://example.com/proxy/a2a/push-notifications/callback/b");
    }

    @Test
    void derivesAddressFromEachRequestAndRequiresTrustedAddressForPush() {
        var factory = new HostedAgentCardFactory(definitions, new A2AProperties(), new ServiceProperties());
        assertThat(factory.card("b", "http://first:8080/context", true).supportedInterfaces().get(0).url())
                .isEqualTo("http://first:8080/context/a2a");
        assertThat(factory.card("b", "https://second:443/context", false).supportedInterfaces().get(0).url())
                .isEqualTo("https://second:443/context/a2a/agents/b");
        assertThatThrownBy(() -> factory.callbackUrl("b")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void intersectsCapabilitiesAndDoesNotInheritBusinessSkills() {
        var global = new A2AProperties();
        global.setStreaming(false);
        global.setPushNotifications(true);
        var skill = new A2AProperties.SkillProperties();
        skill.setId("global");
        global.setSkills(List.of(skill));
        var b = new A2AProperties.HostedCardProperties();
        b.setStreaming(true);
        b.setPushNotifications(false);
        global.getAgents().put("b", b);
        var factory = new HostedAgentCardFactory(definitions, global, new ServiceProperties());
        assertThat(factory.card("a", "http://host", false).capabilities().pushNotifications()).isTrue();
        assertThat(factory.card("b", "http://host", false).capabilities().pushNotifications()).isFalse();
        assertThat(factory.card("b", "http://host", false).capabilities().streaming()).isFalse();
        assertThat(factory.card("a", "http://host", false).skills()).isEmpty();
        global.setPushNotifications(false);
        b.setPushNotifications(true);
        var disabled = new HostedAgentCardFactory(definitions, global, new ServiceProperties());
        assertThat(disabled.card("a", "http://host", false).capabilities().pushNotifications()).isFalse();
        assertThat(disabled.card("b", "http://host", false).capabilities().pushNotifications()).isFalse();
    }

    @Test
    void rejectsUnregisteredCardInvalidPathAndInvalidModes() {
        var properties = new A2AProperties();
        properties.getAgents().put("unknown", new A2AProperties.HostedCardProperties());
        assertThatThrownBy(() -> new HostedAgentCardFactory(definitions, properties, new ServiceProperties()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown");
        properties.getAgents().clear();
        properties.setJsonRpcPath("/custom");
        assertThatThrownBy(() -> new HostedAgentCardFactory(definitions, properties, new ServiceProperties()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("json-rpc-path");
        properties.setJsonRpcPath("/a2a/");
        var b = new A2AProperties.HostedCardProperties();
        b.setDefaultInputModes(List.of());
        properties.getAgents().put("b", b);
        assertThatThrownBy(() -> new HostedAgentCardFactory(definitions, properties, new ServiceProperties()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modes");
    }
}
