/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultCardResolverTest {
    private A2ARemoteAgentCardRegistry registry;
    private DefaultCardResolver resolver;

    @BeforeEach
    void setUp() {
        registry = mock(A2ARemoteAgentCardRegistry.class);
        resolver = new DefaultCardResolver(registry);
    }

    @Test
    void resolveJsonRpcUrlReturnsFirstInterfaceUrl() {
        AgentCard card = mock(AgentCard.class);
        AgentInterface iface = mock(AgentInterface.class);
        when(iface.url()).thenReturn("http://remote.example/a2a");
        when(card.supportedInterfaces()).thenReturn(List.of(iface));
        when(registry.get("agent-a")).thenReturn(Optional.of(
                new A2ARemoteAgentCardRegistry.RemoteAgentEntry("agent-a", card, 30)));
        when(registry.resolveUrl("agent-a")).thenReturn("http://remote.example/a2a");

        assertThat(resolver.resolveJsonRpcUrl("agent-a")).isEqualTo("http://remote.example/a2a");
    }

    @Test
    void resolveCardUrlReturnsBaseUrlPlusWellKnownSuffix() {
        AgentCard card = mock(AgentCard.class);
        when(card.supportedInterfaces()).thenReturn(List.of());
        when(card.name()).thenReturn("agent-a");
        when(registry.get("agent-a")).thenReturn(Optional.of(
                new A2ARemoteAgentCardRegistry.RemoteAgentEntry("agent-a", card, 30)));
        when(registry.resolveUrl("agent-a")).thenReturn("http://remote.example/a2a");

        assertThat(resolver.resolveCardUrl("agent-a"))
                .isEqualTo("http://remote.example/.well-known/agent-card.json");
    }

    @Test
    void returnsEmptyForUnknownAgent() {
        when(registry.get("agent-x")).thenReturn(Optional.empty());
        when(registry.resolveUrl("agent-x")).thenReturn("");

        assertThat(resolver.resolveJsonRpcUrl("agent-x")).isEmpty();
        assertThat(resolver.resolveCardUrl("agent-x")).isEmpty();
        assertThat(resolver.supported("agent-x")).isFalse();
    }

    @Test
    void supportedReturnsTrueWhenRegistryHasAgent() {
        AgentCard card = mock(AgentCard.class);
        when(registry.get("agent-a")).thenReturn(Optional.of(
                new A2ARemoteAgentCardRegistry.RemoteAgentEntry("agent-a", card, 30)));

        assertThat(resolver.supported("agent-a")).isTrue();
    }

    @Test
    void resolveCardUrlHandlesUrlWithoutPath() {
        when(registry.resolveUrl("agent-a")).thenReturn("https://host");
        assertThat(resolver.resolveCardUrl("agent-a"))
                .isEqualTo("https://host/.well-known/agent-card.json");
    }

    @Test
    void resolveCardUrlPreservesPort() {
        when(registry.resolveUrl("agent-a")).thenReturn("https://host:8080/a2a");
        assertThat(resolver.resolveCardUrl("agent-a"))
                .isEqualTo("https://host:8080/.well-known/agent-card.json");
    }

    @Test
    void resolveCardUrlStripsTrailingSlashAndPath() {
        when(registry.resolveUrl("agent-a")).thenReturn("https://host/a2a/");
        assertThat(resolver.resolveCardUrl("agent-a"))
                .isEqualTo("https://host/.well-known/agent-card.json");
    }
}
