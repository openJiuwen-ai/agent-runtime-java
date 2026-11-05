/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Tests of the TTL-driven unified catalog refresher (FEAT-062 L2
 * {@literal §}4): the TTL configuration bounds, the idle behavior without a
 * source bean, the non-gating first pull, failure isolation that keeps the
 * previous catalog, and recovery.
 *
 * @since 0.1.4
 */
class RemoteCatalogRefresherTest {
    @Test
    void rejectsTtlsOutsideTheConfiguredBounds() {
        assertThatThrownBy(() -> refresher(absentProvider(), 999)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid openjiuwen.service.a2a.remote-catalog.refresh-ttl-ms 999: "
                        + "must be within [1000, 30000]");
        assertThatThrownBy(() -> refresher(absentProvider(), 30001)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid openjiuwen.service.a2a.remote-catalog.refresh-ttl-ms 30001: "
                        + "must be within [1000, 30000]");
    }

    @Test
    void acceptsTheConfiguredTtlBoundaries() {
        assertThatCode(() -> refresher(absentProvider(), 1000)).doesNotThrowAnyException();
        assertThatCode(() -> refresher(absentProvider(), 30000)).doesNotThrowAnyException();
    }

    @Test
    void staysIdleWithoutASourceBeanAndNeverTouchesTheRegistry() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        RemoteCatalogRefresher refresher = refresher(absentProvider(), registry, 5000);
        try {
            refresher.start();

            assertThat(registry.snapshot().version()).isZero();
            assertThat(registry.getAll()).isEmpty();
            assertThat(ReflectionTestUtils.getField(refresher, "refreshExecutor")).isNull();
        } finally {
            refresher.shutdown();
        }
    }

    @Test
    void failedInitialPullIsNotAReadinessGateAndKeepsTheCatalogEmpty() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        RemoteCatalogSource source = failingSource();
        RemoteCatalogRefresher refresher = refresher(provider(source), registry, 5000);
        try {
            assertThatCode(refresher::start).doesNotThrowAnyException();

            assertThat(registry.getAll()).isEmpty();
        } finally {
            refresher.shutdown();
        }
    }

    @Test
    void successfulInitialPullAppliesTheSourceSnapshot() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        RemoteCatalogSource source = mock(RemoteCatalogSource.class);
        when(source.load()).thenReturn(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))));
        RemoteCatalogRefresher refresher = refresher(provider(source), registry, 5000);
        try {
            refresher.start();

            assertThat(registry.get("weather")).isPresent();
            assertThat(registry.get("weather").orElseThrow().timeoutSeconds()).isEqualTo(30);
            assertThat(registry.snapshot().version()).isEqualTo(1);
        } finally {
            refresher.shutdown();
        }
    }

    @Test
    void loadFailureKeepsThePreviousCatalogUntilAPullRecovers() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        RemoteCatalogSource source = mock(RemoteCatalogSource.class);
        when(source.load()).thenReturn(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))))
                .thenThrow(new RemoteCatalogLoadException("registry unreachable"))
                .thenReturn(new RemoteCatalogSnapshot(2, List.of(entry("weather", 77))));
        RemoteCatalogRefresher refresher = refresher(provider(source), registry, 5000);
        try {
            refresher.start();
            assertThat(registry.get("weather").orElseThrow().timeoutSeconds()).isEqualTo(30);

            ReflectionTestUtils.invokeMethod(refresher, "refreshOnce", "periodic");
            assertThat(registry.get("weather").orElseThrow().timeoutSeconds()).isEqualTo(30);
            assertThat(registry.snapshot().version()).isEqualTo(1);

            ReflectionTestUtils.invokeMethod(refresher, "refreshOnce", "periodic");
            assertThat(registry.get("weather").orElseThrow().timeoutSeconds()).isEqualTo(77);
            assertThat(registry.snapshot().version()).isEqualTo(2);
        } finally {
            refresher.shutdown();
        }
    }

    @Test
    void unexpectedRuntimeFaultKeepsThePreviousCatalogUntilAPullRecovers() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();
        RemoteCatalogSource source = mock(RemoteCatalogSource.class);
        when(source.load()).thenReturn(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))))
                .thenThrow(new IllegalStateException("source implementation bug"))
                .thenReturn(new RemoteCatalogSnapshot(2, List.of(entry("weather", 77))));
        RemoteCatalogRefresher refresher = refresher(provider(source), registry, 5000);
        try {
            refresher.start();
            assertThat(registry.get("weather").orElseThrow().timeoutSeconds()).isEqualTo(30);

            // an unclassified runtime fault must degrade like the load signal:
            // the previous catalog is kept and the TTL loop stays alive
            ReflectionTestUtils.invokeMethod(refresher, "refreshOnce", "periodic");
            assertThat(registry.get("weather").orElseThrow().timeoutSeconds()).isEqualTo(30);
            assertThat(registry.snapshot().version()).isEqualTo(1);

            ReflectionTestUtils.invokeMethod(refresher, "refreshOnce", "periodic");
            assertThat(registry.get("weather").orElseThrow().timeoutSeconds()).isEqualTo(77);
            assertThat(registry.snapshot().version()).isEqualTo(2);
        } finally {
            refresher.shutdown();
        }
    }

    @Test
    void shutdownStopsTheRefreshLoopAndIsIdempotent() {
        RemoteCatalogSource source = mock(RemoteCatalogSource.class);
        when(source.load()).thenReturn(new RemoteCatalogSnapshot(1, List.of(entry("weather", 30))));
        RemoteCatalogRefresher refresher = refresher(provider(source), 5000);
        refresher.start();
        refresher.shutdown();

        assertThat(ReflectionTestUtils.getField(refresher, "refreshExecutor"))
                .isInstanceOfSatisfying(ScheduledExecutorService.class, ScheduledExecutorService::isShutdown);
        assertThatCode(refresher::shutdown).doesNotThrowAnyException();
    }

    private static RemoteCatalogRefresher refresher(ObjectProvider<RemoteCatalogSource> sourceProvider, long ttl) {
        return refresher(sourceProvider, new A2ARemoteAgentCardRegistry(), ttl);
    }

    private static RemoteCatalogRefresher refresher(ObjectProvider<RemoteCatalogSource> sourceProvider,
            A2ARemoteAgentCardRegistry registry, long ttl) {
        RemoteCatalogProperties properties = new RemoteCatalogProperties();
        properties.setRefreshTtlMs(ttl);
        return new RemoteCatalogRefresher(sourceProvider, registry, properties);
    }

    private static RemoteCatalogSource failingSource() {
        RemoteCatalogSource source = mock(RemoteCatalogSource.class);
        when(source.load()).thenThrow(new RemoteCatalogLoadException("registry unreachable"));
        return source;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RemoteCatalogSource> provider(RemoteCatalogSource source) {
        ObjectProvider<RemoteCatalogSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(source);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RemoteCatalogSource> absentProvider() {
        ObjectProvider<RemoteCatalogSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static RemoteCatalogEntry entry(String agentId, int timeoutSeconds) {
        RemoteInstanceCandidate candidate = new RemoteInstanceCandidate("inst-" + agentId,
                "http://10.0.0.1:8080/" + agentId, 100);
        return new RemoteCatalogEntry(agentId, card(agentId, "http://10.0.0.1:8080/" + agentId), timeoutSeconds, false,
                List.of(candidate), null);
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
