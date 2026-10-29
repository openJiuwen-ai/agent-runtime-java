/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Tests of the unified catalog replacement semantics in
 * {@link A2ARemoteAgentCardRegistry#replaceAll} (FEAT-062 L2 {@literal §}4):
 * source-version idempotency, reference stability for unchanged routing
 * views, removal of missing entries, the version floor, and event publication.
 *
 * @since 0.1.4
 */
class A2ARemoteAgentCardRegistryReplaceAllTest {
    private final List<RemoteAgentCatalogChangedEvent> events = new CopyOnWriteArrayList<>();

    @Test
    void startsWithVersionZeroAndNoAppliedSource() {
        A2ARemoteAgentCardRegistry registry = registry();

        assertThat(registry.snapshot().version()).isZero();
        assertThat(registry.getAll()).isEmpty();
    }

    @Test
    void appliesTheSnapshotAndRaisesTheVersionToTheSourceFloor() {
        A2ARemoteAgentCardRegistry registry = registry();

        registry.replaceAll(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))));

        assertThat(registry.get("weather")).isPresent();
        assertThat(registry.get("weather").orElseThrow().timeoutSeconds()).isEqualTo(30);
        assertThat(registry.snapshot().version()).isEqualTo(1);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).agentId()).isNull();
        assertThat(events.get(0).snapshot().version()).isEqualTo(1);
    }

    @Test
    void sameSourceVersionSnapshotIsSkippedWithoutAnEvent() {
        A2ARemoteAgentCardRegistry registry = registry();
        registry.replaceAll(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))));

        registry.replaceAll(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))));

        assertThat(registry.snapshot().version()).isEqualTo(1);
        assertThat(events).hasSize(1);
    }

    @Test
    void unchangedEntriesKeepTheirEntryInstanceAcrossApplications() {
        A2ARemoteAgentCardRegistry registry = registry();
        registry.replaceAll(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))));
        RemoteAgentEntry before = registry.get("weather").orElseThrow();

        registry.replaceAll(new RemoteCatalogSnapshot(2, List.of(entry("weather", 30))));

        assertThat(registry.get("weather")).containsSame(before);
        assertThat(registry.snapshot().version()).isEqualTo(2);
        assertThat(events).hasSize(2);
    }

    @Test
    void changedRoutingViewsProduceFreshEntryInstances() {
        A2ARemoteAgentCardRegistry registry = registry();
        registry.replaceAll(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))));
        RemoteAgentEntry before = registry.get("weather").orElseThrow();

        registry.replaceAll(new RemoteCatalogSnapshot(2, List.of(entry("weather", 77))));

        assertThat(registry.get("weather").orElseThrow()).isNotSameAs(before);
        assertThat(registry.get("weather").orElseThrow().timeoutSeconds()).isEqualTo(77);
    }

    @Test
    void changedCardContentYieldsFreshEntryEvenWhenRoutingUnchanged() {
        A2ARemoteAgentCardRegistry registry = registry();
        registry.replaceAll(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))));
        RemoteAgentEntry before = registry.get("weather").orElseThrow();

        registry.replaceAll(new RemoteCatalogSnapshot(2, List.of(entryWithCard("weather", 30,
                card("weather", "http://10.0.0.1:8080/weather", "weather-revised")))));

        RemoteAgentEntry after = registry.get("weather").orElseThrow();
        assertThat(after).isNotSameAs(before);
        assertThat(after.card().description()).isEqualTo("weather-revised");
    }

    @Test
    void entriesMissingFromTheSnapshotAreRemoved() {
        A2ARemoteAgentCardRegistry registry = registry();
        registry.replaceAll(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30), entry("travel", 20))));

        registry.replaceAll(new RemoteCatalogSnapshot(2, List.of(entry("weather", 30))));

        assertThat(registry.get("weather")).isPresent();
        assertThat(registry.get("travel")).isEmpty();
        assertThat(registry.snapshot().version()).isEqualTo(2);
    }

    @Test
    void higherSourceVersionIsAdoptedAsTheCatalogVersion() {
        A2ARemoteAgentCardRegistry registry = registry();

        registry.replaceAll(new RemoteCatalogSnapshot(10, List.of(entry("weather", 30))));

        assertThat(registry.snapshot().version()).isEqualTo(10);
    }

    @Test
    void emptySnapshotClearsTheCatalogAsTheUpgradeWindowAllows() {
        A2ARemoteAgentCardRegistry registry = registry();
        registry.replaceAll(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))));

        registry.replaceAll(new RemoteCatalogSnapshot(2, List.of()));

        assertThat(registry.getAll()).isEmpty();
        assertThat(registry.snapshot().version()).isEqualTo(2);
    }

    private A2ARemoteAgentCardRegistry registry() {
        return new A2ARemoteAgentCardRegistry(event -> {
            if (event instanceof RemoteAgentCatalogChangedEvent changed) {
                events.add(changed);
            }
        });
    }

    private static RemoteCatalogEntry entry(String agentId, int timeoutSeconds) {
        return entryWithCard(agentId, timeoutSeconds,
                card(agentId, "http://10.0.0.1:8080/" + agentId, agentId));
    }

    private static RemoteCatalogEntry entryWithCard(String agentId, int timeoutSeconds, AgentCard card) {
        RemoteInstanceCandidate candidate = new RemoteInstanceCandidate("inst-" + agentId,
                "http://10.0.0.1:8080/" + agentId, 100);
        return new RemoteCatalogEntry(agentId, card, timeoutSeconds, false, List.of(candidate), null);
    }

    private static AgentCard card(String name, String endpoint, String description) {
        AgentInterface iface = new AgentInterface("JSONRPC", endpoint, null, "1.0");
        return AgentCard.builder().name(name).description(description).version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of()))
                .defaultInputModes(List.of("text")).defaultOutputModes(List.of("text")).skills(List.of())
                .securitySchemes(Map.of()).securityRequirements(List.of())
                .supportedInterfaces(List.of(iface)).url(endpoint)
                .preferredTransport("JSONRPC").additionalInterfaces(List.of()).build();
    }
}
