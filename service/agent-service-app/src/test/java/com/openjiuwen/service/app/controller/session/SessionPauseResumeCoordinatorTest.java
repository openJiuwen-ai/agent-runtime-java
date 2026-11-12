/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.lifecycle.ActiveStreamInterruptor;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for the pause/resume orchestration state machine (issue #117):
 * debounce, admission-gate rejection, asynchronous acceptance, and the
 * admission slot release on failed continuation.
 *
 * @since 0.1.5
 */
class SessionPauseResumeCoordinatorTest {
    private static final long TEST_TIMEOUT_SECONDS = 5L;

    private DirectExecutor directExecutor;
    private ActiveStreamInterruptor interruptor;
    private ObjectProvider<ServeOrchestrator> orchestratorProvider;
    private ObjectProvider<TaskAdmissionGate> gateProvider;
    private ServeOrchestrator orchestrator;
    private TaskAdmissionGate gate;
    private AtomicInteger interruptCount;

    @BeforeEach
    void setUp() {
        directExecutor = new DirectExecutor();
        interruptCount = new AtomicInteger();
        orchestrator = Mockito.mock(ServeOrchestrator.class);
        orchestratorProvider = providerOf(orchestrator);
        interruptor = new ActiveStreamInterruptor(orchestratorProvider,
                List.of((conversationId, reason) -> interruptCount.incrementAndGet()));
        gate = Mockito.mock(TaskAdmissionGate.class);
        gateProvider = providerOf(gate);
    }

    @AfterEach
    void tearDown() {
        Mockito.reset(orchestrator, gate);
    }

    @Test
    void pauseAcceptsAsynchronouslyAndInterruptsOnce() throws InterruptedException {
        SessionPauseResumeCoordinator coordinator = newCoordinator();
        CountDownLatch interrupted = new CountDownLatch(1);
        interruptor = new ActiveStreamInterruptor(providerOf(null),
                List.of((conversationId, reason) -> {
                    interruptCount.incrementAndGet();
                    interrupted.countDown();
                }));
        SessionPauseResumeCoordinator asyncCoordinator = newCoordinator();

        SessionOperationResult result = asyncCoordinator.pause("session-1", "op-1", "quota");

        assertThat(result.accepted()).isTrue();
        assertThat(result.state()).isEqualTo(SessionState.PAUSED);
        assertThat(result.alreadyInState()).isFalse();
        assertThat(interrupted.await(TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        assertThat(interruptCount.get()).isEqualTo(1);
    }

    @Test
    void pauseWithinDebounceWindowMergesRequests() {
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult first = coordinator.pause("session-2", "op-1", "quota");
        SessionOperationResult second = coordinator.pause("session-2", "op-2", "quota");

        assertThat(first.accepted()).isTrue();
        assertThat(second.accepted()).isTrue();
        assertThat(second.alreadyInState()).isTrue();
        assertThat(interruptCount.get()).isEqualTo(1);
    }

    @Test
    void pauseBlankSessionIdRejected() {
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.pause(" ", "op-1", null);

        assertThat(result.accepted()).isFalse();
        assertThat(interruptCount.get()).isZero();
    }

    @Test
    void resumeAcquiresAdmissionAndContinues() {
        when(gate.tryAcquire()).thenReturn(true);
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.resume("session-3", "op-1");

        assertThat(result.accepted()).isTrue();
        assertThat(result.state()).isEqualTo(SessionState.RUNNING);
        Mockito.verify(gate).tryAcquire();
        Mockito.verify(orchestrator).resumeSession("session-3");
    }

    @Test
    void resumeWithFullGateKeepsSessionPaused() {
        when(gate.tryAcquire()).thenReturn(false);
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.resume("session-4", "op-1");

        assertThat(result.accepted()).isFalse();
        assertThat(result.state()).isEqualTo(SessionState.PAUSED);
        Mockito.verify(orchestrator, Mockito.never()).resumeSession(Mockito.anyString());
    }

    @Test
    void resumeWithoutGateStillContinues() {
        gateProvider = providerOf(null);
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.resume("session-5", "op-1");

        assertThat(result.accepted()).isTrue();
        Mockito.verify(orchestrator).resumeSession("session-5");
    }

    @Test
    void resumeContinuationFailureReleasesAdmissionSlot() {
        when(gate.tryAcquire()).thenReturn(true);
        Mockito.doThrow(new IllegalStateException("continuation failed"))
                .when(orchestrator).resumeSession(Mockito.anyString());
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.resume("session-6", "op-1");

        assertThat(result.accepted()).isTrue();
        Mockito.verify(gate).release();
    }

    @Test
    void resumeContinuationSuccessAlsoReleasesAdmissionSlot() {
        // The continuation runs through the internal execution chain the gate
        // does not track; holding the slot beyond the resume work would leak
        // quota one resume at a time.
        when(gate.tryAcquire()).thenReturn(true);
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.resume("session-gate-ok", "op-1");

        assertThat(result.accepted()).isTrue();
        Mockito.verify(orchestrator).resumeSession("session-gate-ok");
        Mockito.verify(gate).release();
    }

    @Test
    void terminateInterruptsThroughSharedChain() {
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.terminate("session-7", "op-1");

        assertThat(result.accepted()).isTrue();
        assertThat(result.state()).isEqualTo(SessionState.TERMINATED);
        assertThat(interruptCount.get()).isEqualTo(1);
    }

    @Test
    void pausePublishesIntentThroughOrchestratorBeforeInterrupt() {
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.pause("session-pause-intent", "op-9", "quota");

        assertThat(result.accepted()).isTrue();
        org.mockito.ArgumentCaptor<Map<String, Object>> infoCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        Mockito.verify(orchestrator).requestPause(
                org.mockito.ArgumentMatchers.eq("session-pause-intent"), infoCaptor.capture());
        assertThat(infoCaptor.getValue())
                .containsEntry("operator", "op-9")
                .containsEntry("reason", "quota");
        assertThat(infoCaptor.getValue()).containsKey("requestedAt");
        // The shared cancel chain still runs with the user-request reason.
        Mockito.verify(orchestrator).cancelActive("session-pause-intent");
        assertThat(interruptCount.get()).isEqualTo(1);
    }

    @Test
    void pauseIntentPublishFailureAbortsPauseAndRollsBackState() {
        // Interrupting without a published intent would end the stream with no
        // persisted PAUSED cursor: the pause must abort instead, keeping the
        // session running and dropping the recorded paused state.
        Mockito.doThrow(new IllegalStateException("publish failed"))
                .when(orchestrator).requestPause(Mockito.anyString(), Mockito.anyMap());
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.pause("session-pause-fail", "op-10", "quota");

        assertThat(result.accepted()).isTrue();
        Mockito.verify(orchestrator, Mockito.never()).cancelActive("session-pause-fail");
        assertThat(interruptCount.get()).isZero();
        assertThat(coordinator.managedState("session-pause-fail")).isNull();
    }

    @Test
    void pauseWithoutOrchestratorAbortsPauseAndRollsBackState() {
        orchestratorProvider = providerOf(null);
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.pause("session-pause-no-orch", "op-14", "quota");

        assertThat(result.accepted()).isTrue();
        assertThat(interruptCount.get()).isZero();
        assertThat(coordinator.managedState("session-pause-no-orch")).isNull();
    }

    @Test
    void pauseCarriesCallerTenantInsideIntentMetadata() {
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        coordinator.pause("session-pause-tenant", "op-15", "tenant-a", "quota");

        org.mockito.ArgumentCaptor<Map<String, Object>> infoCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        Mockito.verify(orchestrator).requestPause(
                org.mockito.ArgumentMatchers.eq("session-pause-tenant"), infoCaptor.capture());
        assertThat(infoCaptor.getValue())
                .containsEntry(SessionPauseResumeCoordinator.TENANT_ID_KEY, "tenant-a");
    }

    @Test
    void pauseWithoutTenantOmitsTenantKeyFromIntentMetadata() {
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        coordinator.pause("session-pause-no-tenant", "op-16", null, "quota");

        org.mockito.ArgumentCaptor<Map<String, Object>> infoCaptor =
                org.mockito.ArgumentCaptor.forClass(Map.class);
        Mockito.verify(orchestrator).requestPause(
                org.mockito.ArgumentMatchers.eq("session-pause-no-tenant"), infoCaptor.capture());
        assertThat(infoCaptor.getValue()).doesNotContainKey(SessionPauseResumeCoordinator.TENANT_ID_KEY);
    }

    @Test
    void terminateDoesNotPublishPauseIntent() {
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        coordinator.terminate("session-terminate-only", "op-11");

        Mockito.verify(orchestrator, Mockito.never())
                .requestPause(Mockito.anyString(), Mockito.anyMap());
    }

    @Test
    void failedResumeOfNeverPausedSessionDoesNotMarkItPaused() {
        // A session the coordinator never recorded as paused (e.g. admitted
        // through the resume path while idle) must not be flipped into the
        // PAUSED registry state by a failed continuation.
        when(gate.tryAcquire()).thenReturn(true);
        Mockito.doThrow(new IllegalStateException("no paused cursor"))
                .when(orchestrator).resumeSession(Mockito.anyString());
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        SessionOperationResult result = coordinator.resume("session-idle-resume", "op-17");

        assertThat(result.accepted()).isTrue();
        assertThat(coordinator.managedState("session-idle-resume")).isNull();
    }

    @Test
    void failedResumeOfPausedSessionRestoresPausedState() {
        when(gate.tryAcquire()).thenReturn(true);
        Mockito.doThrow(new IllegalStateException("no paused cursor"))
                .when(orchestrator).resumeSession(Mockito.anyString());
        SessionPauseResumeCoordinator coordinator = newCoordinator();
        coordinator.pause("session-failed-resume", "op-18", "quota");

        coordinator.resume("session-failed-resume", "op-18");

        assertThat(coordinator.managedState("session-failed-resume")).isEqualTo(SessionState.PAUSED);
    }

    @Test
    void compactSessionDelegatesSynchronouslyToOrchestrator() {
        Map<String, Object> compactResult = Map.of("session_id", "session-compact", "result", "compressed");
        when(orchestrator.compactSession(Mockito.eq("session-compact"), Mockito.any()))
                .thenReturn(compactResult);
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        Map<String, Object> result = coordinator.compactSession("session-compact", "op-12", null);

        assertThat(result).containsEntry("result", "compressed");
        Mockito.verify(orchestrator).compactSession(Mockito.eq("session-compact"), Mockito.any());
    }

    @Test
    void compactSessionWithoutOrchestratorFails() {
        orchestratorProvider = providerOf(null);
        SessionPauseResumeCoordinator coordinator = newCoordinator();

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> coordinator.compactSession("session-compact-none", "op-13", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No ServeOrchestrator available");
    }

    private SessionPauseResumeCoordinator newCoordinator() {
        return new SessionPauseResumeCoordinator(orchestratorProvider, interruptor, gateProvider, directExecutor);
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    /** Executor running work inline so asynchronous paths become deterministic. */
    private static final class DirectExecutor implements Executor {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
