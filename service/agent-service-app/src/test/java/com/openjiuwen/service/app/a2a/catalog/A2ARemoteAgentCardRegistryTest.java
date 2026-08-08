/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.a2a.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.IntStream;

/** Tests remote Agent Card catalog snapshots and update publication. */
class A2ARemoteAgentCardRegistryTest {
    @Test
    void initialSnapshotIsEmptyAndImmutable() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry();

        RemoteAgentCatalogSnapshot snapshot = registry.snapshot();

        assertThat(snapshot.version()).isZero();
        assertThat(snapshot.entries()).isEmpty();
        assertThatThrownBy(() -> snapshot.entries().add(entry("other")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void registrationPublishesCompleteSortedSnapshots() {
        List<RemoteAgentCatalogChangedEvent> events = new CopyOnWriteArrayList<>();
        A2ARemoteAgentCardRegistry registry = registryWithEvents(events);

        registry.register("weather", mock(AgentCard.class), 30, true);
        registry.register("balance", mock(AgentCard.class), 60, false);

        assertThat(events).hasSize(2);
        assertThat(events.get(0).snapshot().version()).isEqualTo(1L);
        assertThat(events.get(0).snapshot().entries()).extracting(RemoteAgentEntry::name).containsExactly("weather");
        assertThat(events.get(1).snapshot().version()).isEqualTo(2L);
        assertThat(events.get(1).snapshot().entries()).extracting(RemoteAgentEntry::name).containsExactly("balance",
                "weather");
        assertThat(registry.getAll()).containsExactlyElementsOf(events.get(1).snapshot().entries());
    }

    @Test
    void replacingSameNameCreatesNewVersion() {
        List<RemoteAgentCatalogChangedEvent> events = new CopyOnWriteArrayList<>();
        A2ARemoteAgentCardRegistry registry = registryWithEvents(events);
        AgentCard firstCard = mock(AgentCard.class);
        AgentCard secondCard = mock(AgentCard.class);

        registry.register("transfer", firstCard, 30, false);
        registry.register("transfer", secondCard, 90, true);

        assertThat(registry.snapshot().version()).isEqualTo(2L);
        assertThat(registry.snapshot().entries()).singleElement().satisfies(entry -> {
            assertThat(entry.card()).isSameAs(secondCard);
            assertThat(entry.timeoutSeconds()).isEqualTo(90);
            assertThat(entry.isStreaming()).isTrue();
        });
        assertThat(events).extracting(event -> event.snapshot().version()).containsExactly(1L, 2L);
    }

    @Test
    void concurrentRegistrationProducesUniqueCompleteVersions() {
        List<RemoteAgentCatalogChangedEvent> events = new CopyOnWriteArrayList<>();
        A2ARemoteAgentCardRegistry registry = registryWithEvents(events);

        IntStream.range(0, 32).parallel()
                .forEach(index -> registry.register("agent-" + index, mock(AgentCard.class), 30, false));

        assertThat(registry.snapshot().version()).isEqualTo(32L);
        assertThat(registry.snapshot().entries()).hasSize(32);
        assertThat(events).hasSize(32);
        assertThat(events).extracting(event -> event.snapshot().version()).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, 32).mapToObj(Long::valueOf).toList());
        assertThat(events)
                .allSatisfy(event -> assertThat(event.snapshot().entries()).hasSize((int) event.snapshot().version()));
    }

    @Test
    void publicationFailureDoesNotFailCompletedRegistryUpdate() {
        A2ARemoteAgentCardRegistry registry = new A2ARemoteAgentCardRegistry(event -> {
            throw new IllegalStateException("listener failed");
        });

        registry.register("balance", mock(AgentCard.class));

        assertThat(registry.snapshot().version()).isEqualTo(1L);
        assertThat(registry.get("balance")).isPresent();
    }

    private static A2ARemoteAgentCardRegistry registryWithEvents(List<RemoteAgentCatalogChangedEvent> events) {
        ApplicationEventPublisher publisher = event -> {
            if (event instanceof RemoteAgentCatalogChangedEvent catalogEvent) {
                events.add(catalogEvent);
                return;
            }
            throw new IllegalArgumentException("Unexpected event type: " + event.getClass().getName());
        };
        return new A2ARemoteAgentCardRegistry(publisher);
    }

    private static RemoteAgentEntry entry(String name) {
        return new RemoteAgentEntry(name, mock(AgentCard.class), 30, false);
    }
}
