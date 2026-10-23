/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.hosting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.openjiuwen.service.app.a2a.catalog.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentCatalogChangedEvent;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentCatalogSnapshot;
import com.openjiuwen.service.app.a2a.catalog.RemoteAgentEntry;
import com.openjiuwen.service.app.a2a.catalog.RemoteInstanceCandidate;
import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.app.config.A2AProperties.HostedCardProperties;
import com.openjiuwen.service.app.config.A2AProperties.RemoteAgentProperties;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests of the inherited-entry alignment in {@link HostedRemoteAgentCatalogs}
 * (FEAT-062 L2 {@literal §}4): instance-local directories track the global
 * snapshot including instance candidates, removals never touch local names,
 * instance-scoped and stale events are ignored, and agents without local
 * configuration share the process-wide registry.
 *
 * @since 0.1.4
 */
class HostedRemoteAgentCatalogsAlignmentTest {
    @Test
    void inheritedEntriesTrackTheGlobalSnapshotIncludingCandidates() {
        Context context = context(propertiesWithLocalTool(), "a");
        context.global().register("weather", card("weather", "http://10.0.0.1:8080/a2a"), 30, false);
        assertThat(context.hosted().catalog("a").get("weather").orElseThrow().timeoutSeconds()).isEqualTo(30);

        List<RemoteInstanceCandidate> candidates = List.of(new RemoteInstanceCandidate("i-9",
                "http://10.0.0.9:8080/a2a", 50));
        context.global()
                .register(new RemoteAgentEntry("weather", card("weather", "http://10.0.0.9:8080/a2a"), 77, true, null,
                        candidates));

        RemoteAgentEntry updated = context.hosted().catalog("a").get("weather").orElseThrow();
        assertThat(updated.timeoutSeconds()).isEqualTo(77);
        assertThat(updated.isStreaming()).isTrue();
        assertThat(updated.candidates()).isEqualTo(candidates);

        HostedRemoteAgentCatalogs rebuilt = new HostedRemoteAgentCatalogs(definitions("a"), propertiesWithLocalTool(),
                context.global(), event -> {
                });
        assertThat(rebuilt.catalog("a").get("weather").orElseThrow().candidates()).isEqualTo(candidates);
    }

    @Test
    void globalRemovalRemovesInheritedEntriesButNeverLocalNames() {
        Context context = context(propertiesWithLocalTool(), "a");
        context.global().register("weather", card("weather", "http://10.0.0.1:8080/a2a"), 30, false);
        context.hosted().catalog("a").register("local-tool", card("local-tool", "http://10.0.0.2:8080/a2a"), 11, false);
        assertThat(context.instanceEvents()).hasSize(2);
        assertThat(context.instanceEvents()).extracting(RemoteAgentCatalogChangedEvent::agentId).containsOnly("a");

        assertThat(context.global().remove("weather")).isTrue();

        assertThat(context.hosted().catalog("a").get("weather")).isEmpty();
        assertThat(context.hosted().catalog("a").get("local-tool")).isPresent();
        assertThat(context.hosted().catalog("a").get("local-tool").orElseThrow().timeoutSeconds()).isEqualTo(11);
    }

    @Test
    void globalReRegistrationNeverOverwritesLocalNames() {
        Context context = context(propertiesWithLocalTool(), "a");
        context.hosted().catalog("a").register("local-tool", card("local-tool", "http://10.0.0.2:8080/a2a"), 11, false);

        context.global().register("local-tool", card("global-local-tool", "http://10.0.0.3:8080/a2a"), 99, false);

        assertThat(context.hosted().catalog("a").get("local-tool").orElseThrow().timeoutSeconds()).isEqualTo(11);
        assertThat(context.hosted().catalog("a").get("local-tool").orElseThrow().card().name()).isEqualTo("local-tool");
    }

    @Test
    void instanceScopedAndStaleEventsAreIgnoredWithoutAdvancingTheGuard() {
        Context context = context(propertiesWithLocalTool(), "a");
        context.global().register("weather", card("weather", "http://10.0.0.1:8080/a2a"), 30, false);
        long globalVersion = context.global().snapshot().version();

        context.hosted().onGlobalCatalogChanged(new RemoteAgentCatalogChangedEvent(
                new RemoteAgentCatalogSnapshot(globalVersion, List.of())));
        context.hosted().onGlobalCatalogChanged(new RemoteAgentCatalogChangedEvent(
                new RemoteAgentCatalogSnapshot(globalVersion + 5, List.of()), "a"));

        assertThat(context.hosted().catalog("a").get("weather")).isPresent();

        context.global().register("weather", card("weather", "http://10.0.0.4:8080/a2a"), 88, false);

        assertThat(context.hosted().catalog("a").get("weather").orElseThrow().timeoutSeconds()).isEqualTo(88);
    }

    @Test
    void agentsWithoutLocalConfigurationShareTheGlobalRegistry() {
        Context context = context(new A2AProperties(), "a", "b");

        assertThat(context.hosted().catalog("a")).isSameAs(context.global());
        assertThat(context.hosted().catalog("b")).isSameAs(context.global());
        assertThat(context.hosted().hasLocalConfiguration("a")).isFalse();
    }

    private Context context(A2AProperties properties, String... agentIds) {
        AtomicReference<HostedRemoteAgentCatalogs> hook = new AtomicReference<>();
        List<RemoteAgentCatalogChangedEvent> instanceEvents = new CopyOnWriteArrayList<>();
        A2ARemoteAgentCardRegistry global = new A2ARemoteAgentCardRegistry(event -> {
            if (event instanceof RemoteAgentCatalogChangedEvent changed && hook.get() != null) {
                hook.get().onGlobalCatalogChanged(changed);
            }
        });
        HostedRemoteAgentCatalogs hosted = new HostedRemoteAgentCatalogs(definitions(agentIds), properties, global,
                event -> {
                    if (event instanceof RemoteAgentCatalogChangedEvent changed) {
                        instanceEvents.add(changed);
                    }
                });
        hook.set(hosted);
        return new Context(global, hosted, instanceEvents);
    }

    private record Context(A2ARemoteAgentCardRegistry global, HostedRemoteAgentCatalogs hosted,
            List<RemoteAgentCatalogChangedEvent> instanceEvents) {
    }

    private static HostedAgentDefinitions definitions(String... agentIds) {
        HostedAgentDefinitions.Builder builder = HostedAgentDefinitions.builder();
        for (String agentId : agentIds) {
            builder.add(agentId, mock(AgentHandler.class));
        }
        return builder.build();
    }

    private static A2AProperties propertiesWithLocalTool() {
        A2AProperties properties = new A2AProperties();
        HostedCardProperties hostedCardProperties = new HostedCardProperties();
        hostedCardProperties.setRemoteAgents(List.of(remote("local-tool")));
        properties.getAgents().put("a", hostedCardProperties);
        return properties;
    }

    private static RemoteAgentProperties remote(String name) {
        RemoteAgentProperties remote = new RemoteAgentProperties();
        remote.setName(name);
        remote.setUrl("http://127.0.0.1:1/global");
        remote.setTimeoutSeconds(77);
        remote.setStreaming(true);
        return remote;
    }

    private static AgentCard card(String name, String endpoint) {
        AgentInterface iface = new AgentInterface("JSONRPC", endpoint, null, "1.0");
        return AgentCard.builder().name(name).description(name).version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .securitySchemes(Map.of()).securityRequirements(List.of())
                .supportedInterfaces(List.of(iface)).url(endpoint)
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }
}
