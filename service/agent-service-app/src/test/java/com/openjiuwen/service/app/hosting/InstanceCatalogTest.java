/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.config.DefaultAgentServiceIdentity;
import com.openjiuwen.service.app.controller.a2a.StandardAgentCardFactory;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.paths.A2AServicePaths;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * Tests of the registrable instance views (FEAT-062 L2 {@literal §}4): the
 * standard single-process catalog exposing one JSON-RPC descriptor, the hosted
 * catalog exposing every published runtime with per-agent paths, and the
 * not-ready failure while the hosted catalog is unpublished.
 *
 * @since 0.1.4
 */
class InstanceCatalogTest {

    @Test
    void standardCatalogExposesTheSingleJsonRpcInstance() {
        StandardAgentCardFactory cardFactory = mock(StandardAgentCardFactory.class);
        AgentCard card = card("demo-app", "http://10.1.2.3:8080/a2a");
        when(cardFactory.card("")).thenReturn(card);
        StandardInstanceCatalog catalog = new StandardInstanceCatalog(cardFactory,
                new DefaultAgentServiceIdentity("demo-app"), "/a2a");

        List<InstanceDescriptor> instances = catalog.instances();

        assertThat(instances).hasSize(1);
        assertThat(instances.get(0).agentId()).isEqualTo("demo-app");
        assertThat(instances.get(0).endpointPath()).isEqualTo("/a2a");
        assertThat(instances.get(0).card()).isSameAs(card);
    }

    @Test
    void hostedCatalogExposesEveryPublishedRuntimeInRegistrationOrder() {
        AgentHandler travelHandler = mock(AgentHandler.class);
        AgentHandler weatherHandler = mock(AgentHandler.class);
        HostedRuntimeCatalog catalog = new HostedRuntimeCatalog(HostedAgentDefinitions.builder()
                .add("travel", travelHandler).add("weather", weatherHandler).build());
        HostedAgentRuntime travelRuntime = mock(HostedAgentRuntime.class);
        when(travelRuntime.agentId()).thenReturn("travel");
        when(travelRuntime.handler()).thenReturn(travelHandler);
        HostedAgentRuntime weatherRuntime = mock(HostedAgentRuntime.class);
        when(weatherRuntime.agentId()).thenReturn("weather");
        when(weatherRuntime.handler()).thenReturn(weatherHandler);
        catalog.publish(List.of(travelRuntime, weatherRuntime));
        HostedAgentCardFactory cardFactory = mock(HostedAgentCardFactory.class);
        when(cardFactory.card("travel", "", false)).thenReturn(card("travel", "http://10.1.2.3:8080"));
        when(cardFactory.card("weather", "", false)).thenReturn(card("weather", "http://10.1.2.3:8080"));
        HostedInstanceCatalog instanceCatalog = new HostedInstanceCatalog(catalog, cardFactory);

        List<InstanceDescriptor> instances = instanceCatalog.instances();

        assertThat(instances).hasSize(2);
        assertThat(instances.get(0).agentId()).isEqualTo("travel");
        assertThat(instances.get(0).endpointPath()).isEqualTo(A2AServicePaths.HOSTED_AGENTS + "/travel");
        assertThat(instances.get(1).agentId()).isEqualTo("weather");
        assertThat(instances.get(1).endpointPath()).isEqualTo(A2AServicePaths.HOSTED_AGENTS + "/weather");
    }

    @Test
    void unpublishedHostedCatalogFailsAsNotReady() {
        HostedRuntimeCatalog catalog = new HostedRuntimeCatalog(
                HostedAgentDefinitions.builder().add("travel", mock(AgentHandler.class)).build());
        HostedInstanceCatalog instanceCatalog = new HostedInstanceCatalog(catalog, mock(HostedAgentCardFactory.class));

        assertThatThrownBy(instanceCatalog::instances)
                .isInstanceOf(HostedIngressResolver.SelectionException.class)
                .isInstanceOfSatisfying(HostedIngressResolver.SelectionException.class, selectionException -> {
                    assertThat(selectionException.status()).isEqualTo(503);
                    assertThat(selectionException.reason()).isEqualTo("AGENT_NOT_READY");
                });
    }

    private static AgentCard card(String name, String endpoint) {
        AgentInterface iface = new AgentInterface("JSONRPC", endpoint + A2AServicePaths.A2A_JSONRPC, null, "1.0");
        return AgentCard.builder().name(name).description(name).version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .securitySchemes(Map.of()).securityRequirements(List.of())
                .supportedInterfaces(List.of(iface)).url(endpoint)
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }
}
