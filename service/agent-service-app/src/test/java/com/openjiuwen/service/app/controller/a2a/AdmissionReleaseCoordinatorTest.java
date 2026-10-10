/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.config.A2AProperties;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionListener;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionService;

import org.a2aproject.sdk.server.events.MainEventBus;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/**
 * Unit tests for {@link AdmissionReleaseCoordinator} — DFX-006 test plan
 * U-01..U-08 and U-13.
 */
class AdmissionReleaseCoordinatorTest {
    /** Fast timing: ttl 60ms, resort step 50ms, force cap 250ms, stall deadline 80ms. */
    private static final AdmissionReleaseCoordinator.Timing FAST =
            new AdmissionReleaseCoordinator.Timing(60L, 50L, 250L, 80L);

    @Test
    void leaseFifoReleasesOldestRoundFirst() {
        TaskAdmissionGate gate = gate(30);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate,
                mock(MainEventBus.class), null, listener, FAST);

        coordinator.register("t-1", "conv-a");
        coordinator.register("t-1", "conv-b");

        coordinator.consequenceLanded("t-1");
        verify(listener).onReleased("t-1", "conv-a");
        verify(listener, never()).onReleased("t-1", "conv-b");

        coordinator.consequenceLanded("t-1");
        verify(listener).onReleased("t-1", "conv-b");
        verify(gate, times(2)).release();
        assertThat(coordinator.pendingReleaseCount()).isZero();
    }

    @Test
    void releaseHappensExactlyOnceWhenConsequenceAndTtlRace() {
        TaskAdmissionGate gate = gate(30);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate,
                mock(MainEventBus.class), provider(true), listener, FAST);

        coordinator.register("t-1", "conv-a").ifPresent(coordinator::completeRound);
        coordinator.consequenceLanded("t-1");
        coordinator.consequenceLanded("t-1");

        awaitTrue(() -> false, 200L);
        verify(gate, times(1)).release();
        verify(listener, times(1)).onReleased("t-1", "conv-a");
        assertThat(coordinator.pendingReleaseCount()).isZero();
    }

    @Test
    void ttlReleasesWhenTaskAlreadyFinalizedInStore() {
        TaskAdmissionGate gate = gate(30);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate,
                mock(MainEventBus.class), provider(true), listener, FAST);

        coordinator.register("t-1", "conv-a").ifPresent(coordinator::completeRound);
        assertThat(coordinator.pendingReleaseCount()).isEqualTo(1);

        awaitTrue(() -> coordinator.pendingReleaseCount() == 0, 400L);
        verify(gate).release();
        verify(listener).onReleased("t-1", "conv-a");
    }

    @Test
    void ttlReleasesWhenMainDequeDrained() {
        TaskAdmissionGate gate = gate(30);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate,
                mock(MainEventBus.class), provider(false), listener, FAST);

        coordinator.register("t-1", "conv-a").ifPresent(coordinator::completeRound);

        awaitTrue(() -> coordinator.pendingReleaseCount() == 0, 400L);
        verify(gate).release();
        verify(listener).onReleased("t-1", "conv-a");
    }

    @Test
    void ttlResortsWhileConsequencesPendingThenForceCapReleases() {
        TaskAdmissionGate gate = gate(30);
        MainEventBus bus = mock(MainEventBus.class);
        when(bus.size()).thenReturn(1);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate, bus,
                provider(false), null, FAST);

        coordinator.register("t-1", "conv-a").ifPresent(coordinator::completeRound);

        awaitTrue(() -> false, 150L);
        verify(gate, never()).release();
        assertThat(coordinator.pendingReleaseCount()).isEqualTo(1);

        awaitTrue(() -> coordinator.pendingReleaseCount() == 0, 600L);
        verify(gate).release();
    }

    @Test
    void nullProviderDegradesToDequeDrainAndForceCap() {
        TaskAdmissionGate gate = gate(30);
        MainEventBus bus = mock(MainEventBus.class);
        when(bus.size()).thenReturn(1);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate, bus,
                null, null, FAST);

        coordinator.register("t-1", "conv-a").ifPresent(coordinator::completeRound);

        awaitTrue(() -> false, 150L);
        verify(gate, never()).release();

        when(bus.size()).thenReturn(0);
        awaitTrue(() -> coordinator.pendingReleaseCount() == 0, 600L);
        verify(gate).release();
    }

    @Test
    void forceCapDerivedAsMaxOfFloorAndDoubleTtl() {
        long[][] cases = {
            {30_000L, 20 * 60_000L},
            {5 * 60_000L, 20 * 60_000L},
            {10 * 60_000L, 20 * 60_000L},
            {15 * 60_000L, 30 * 60_000L},
            {25 * 60_000L, 50 * 60_000L}
        };
        for (long[] leaseCase : cases) {
            assertThat(AdmissionReleaseCoordinator.forceCapFor(leaseCase[0])).isEqualTo(leaseCase[1]);
        }
        assertThat(AdmissionReleaseCoordinator.defaultTiming(null).ttlMs())
                .isEqualTo(AdmissionReleaseCoordinator.DEFAULT_TTL_MS);
        A2AProperties properties = new A2AProperties();
        properties.setAdmissionReleaseTtl(Duration.ofSeconds(30));
        assertThat(AdmissionReleaseCoordinator.defaultTiming(properties).ttlMs()).isEqualTo(30_000L);
    }

    @Test
    void disabledTtlNeverReleasesWithoutConsequenceLanding() {
        TaskAdmissionGate gate = gate(30);
        AdmissionReleaseCoordinator.Timing disabled =
                new AdmissionReleaseCoordinator.Timing(0L, 50L, 250L, 80L);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate,
                mock(MainEventBus.class), provider(true), null, disabled);

        coordinator.register("t-1", "conv-a").ifPresent(coordinator::completeRound);

        awaitTrue(() -> false, 300L);
        verify(gate, never()).release();
        assertThat(coordinator.pendingReleaseCount()).isEqualTo(1);

        coordinator.consequenceLanded("t-1");
        verify(gate).release();
    }

    @Test
    void stallDetectedOnlyWithBacklogAndStaleHeartbeat() throws InterruptedException {
        MainEventBus bus = mock(MainEventBus.class);
        when(bus.size()).thenReturn(1);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate(30), bus,
                null, null, FAST);

        assertThat(coordinator.checkEventSafety()).isEmpty();

        Thread.sleep(160L);
        assertThat(coordinator.checkEventSafety())
                .contains(TaskAdmissionService.BUSINESS_CODE_EVENT_QUEUE_STATE_UNAVAILABLE);

        coordinator.heartbeat();
        assertThat(coordinator.checkEventSafety()).isEmpty();

        when(bus.size()).thenReturn(0);
        Thread.sleep(160L);
        assertThat(coordinator.checkEventSafety()).isEmpty();
    }

    @Test
    void poolExhaustionAttributionFollowsPendingReleaseCount() {
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate(30),
                mock(MainEventBus.class), null, null, FAST);

        var leaseOne = coordinator.register("t-1", "conv-a").orElseThrow();
        coordinator.register("t-2", "conv-b");
        assertThat(coordinator.classifyPoolExhaustion())
                .isEqualTo(TaskAdmissionService.BUSINESS_CODE_CONCURRENCY_LIMIT_REACHED);

        coordinator.completeRound(leaseOne);
        assertThat(coordinator.pendingReleaseCount()).isEqualTo(1);
        assertThat(coordinator.classifyPoolExhaustion())
                .isEqualTo(TaskAdmissionService.BUSINESS_CODE_EVENT_QUEUE_OVERLOADED);

        coordinator.consequenceLanded("t-1");
        assertThat(coordinator.classifyPoolExhaustion())
                .isEqualTo(TaskAdmissionService.BUSINESS_CODE_CONCURRENCY_LIMIT_REACHED);
    }

    @Test
    void minusOnePassThroughReleasesImmediatelyAndPairsListeners() {
        TaskAdmissionGate gate = gate(-1);
        when(gate.tryAcquire()).thenReturn(true);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate,
                mock(MainEventBus.class), null, listener, FAST);

        assertThat(coordinator.tryAcquire()).isTrue();
        assertThat(coordinator.checkEventSafety()).isEmpty();

        coordinator.register("t-1", "conv-a").ifPresent(coordinator::completeRound);

        verify(gate).release();
        verify(listener).onReleased("t-1", "conv-a");
        assertThat(coordinator.pendingReleaseCount()).isZero();
    }

    @Test
    void missingGateCoordinatorIsFullNoOp() {
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(null,
                mock(MainEventBus.class), null, null, FAST);

        assertThat(coordinator.tryAcquire()).isTrue();
        coordinator.release();
        coordinator.register("t-1", "conv-a").ifPresent(coordinator::completeRound);
        coordinator.consequenceLanded("t-1");
        coordinator.heartbeat();

        assertThat(coordinator.checkEventSafety()).isEmpty();
        assertThat(coordinator.pendingReleaseCount()).isZero();
    }

    @Test
    void zeroLimitClassifiesEveryRejectionAsConcurrencyLimit() {
        TaskAdmissionGate gate = gate(0);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate,
                mock(MainEventBus.class), null, null, FAST);

        assertThat(coordinator.tryAcquire()).isFalse();
        assertThat(coordinator.classifyPoolExhaustion())
                .isEqualTo(TaskAdmissionService.BUSINESS_CODE_CONCURRENCY_LIMIT_REACHED);
    }

    @Test
    void completeRoundMarksTheHandleOwnLeaseNotTheOldestUnmarked() {
        TaskAdmissionGate gate = gate(30);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate,
                mock(MainEventBus.class), null, null, FAST);

        var roundOne = coordinator.register("t-1", "conv-a").orElseThrow();
        var roundTwo = coordinator.register("t-1", "conv-a").orElseThrow();

        // Fast continuation: round two's finally runs before round one's.
        // The handle must pin the completion to round two's lease — the
        // oldest-unmarked scan would have marked round one instead.
        coordinator.completeRound(roundTwo);
        assertThat(roundTwo.isPendingRelease).isTrue();
        assertThat(roundOne.isPendingRelease).isFalse();
        assertThat(coordinator.pendingReleaseCount()).isEqualTo(1);

        coordinator.completeRound(roundOne);
        assertThat(roundOne.isPendingRelease).isTrue();
        assertThat(coordinator.pendingReleaseCount()).isEqualTo(2);

        // Consequence landing still releases in registration (FIFO) order.
        coordinator.consequenceLanded("t-1");
        assertThat(roundOne.released.get()).isTrue();
        assertThat(roundTwo.released.get()).isFalse();
    }

    private static TaskAdmissionGate gate(int limit) {
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(limit);
        return gate;
    }

    private static TaskStateProvider provider(boolean isFinalized) {
        TaskStateProvider provider = mock(TaskStateProvider.class);
        when(provider.isTaskFinalized(any())).thenReturn(isFinalized);
        return provider;
    }

    private static void awaitTrue(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && !condition.getAsBoolean()) {
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                return;
            }
        }
    }
}
