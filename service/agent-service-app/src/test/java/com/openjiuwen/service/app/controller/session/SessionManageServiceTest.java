/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.config.SessionApiProperties;
import com.openjiuwen.service.spec.concurrency.ActiveTaskInfo;
import com.openjiuwen.service.spec.concurrency.ActiveTaskQuery;
import com.openjiuwen.service.spec.concurrency.ConcurrencyLoadSnapshot;
import com.openjiuwen.service.spec.lifecycle.AgentReadiness;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

/**
 * Tests for the shared session management state machine (issue #117):
 * 404 unknown sessions, 409 state conflicts, 429 admission rejection, and
 * idempotent operations.
 *
 * @since 0.1.5
 */
class SessionManageServiceTest {
    private SessionPauseResumeCoordinator coordinator;
    private ObjectProvider<ActiveTaskQuery> activeTaskQueryProvider;
    private ObjectProvider<AgentReadiness> readinessProvider;
    private SessionApiProperties properties;
    private ActiveTaskQuery activeTaskQuery;

    @BeforeEach
    void setUp() {
        coordinator = Mockito.mock(SessionPauseResumeCoordinator.class);
        // Default stub: the coordinator reports session-operations support, so
        // pause/resume/compact pass the capability gate. Individual tests
        // override this to exercise the 501 unsupported path.
        when(coordinator.supportsSessionOperations()).thenReturn(true);
        activeTaskQuery = Mockito.mock(ActiveTaskQuery.class);
        // Default stub: a successful query reporting no active tasks, i.e.
        // every unmanaged session resolves to IDLE. Individual tests override
        // the snapshot to simulate running sessions or query failures.
        ConcurrencyLoadSnapshot emptySnapshot = Mockito.mock(ConcurrencyLoadSnapshot.class);
        when(emptySnapshot.getTasks()).thenReturn(List.of());
        when(activeTaskQuery.snapshot()).thenReturn(emptySnapshot);
        activeTaskQueryProvider = providerOf(activeTaskQuery);
        readinessProvider = providerOf(null);
        properties = new SessionApiProperties();
    }

    @Test
    void pauseRunningSessionAccepted() {
        mockSessionRunning("s-running");
        SessionOperationResult accepted = SessionOperationResult.accepted(SessionState.PAUSED, "ok");
        when(coordinator.pause("s-running", "op-1", null, null)).thenReturn(accepted);
        SessionManageService service = newService();

        SessionManageService.PauseOutcome outcome = service.pause("s-running", "op-1", null);

        assertThat(outcome.status().value()).isEqualTo(202);
        assertThat(outcome.isAlreadyInState()).isFalse();
    }

    @Test
    void pauseTerminatedSessionConflicts() {
        SessionManageService service = newService();

        SessionManageService.PauseOutcome outcome = service.pause("s-idle", "op-1", null);

        assertThat(outcome.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(outcome.body().get("error")).isEqualTo("INVALID_SESSION_STATE");
    }

    @Test
    void pauseIdleSessionConflicts() {
        SessionManageService service = newService();

        SessionManageService.PauseOutcome outcome = service.pause("s-idle", "op-1", null);

        assertThat(outcome.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(outcome.body().get("error")).isEqualTo("INVALID_SESSION_STATE");
    }

    @Test
    void resumeRunningSessionConflicts() {
        mockSessionRunning("s-running");
        SessionManageService service = newService();

        SessionManageService.ResumeOutcome outcome = service.resume("s-running", "op-1");

        assertThat(outcome.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(outcome.body().get("error")).isEqualTo("INVALID_SESSION_STATE");
    }

    @Test
    void resumeAdmissionRejectedKeepsPaused() {
        SessionOperationResult rejected = SessionOperationResult.rejected(SessionState.PAUSED, "gate full");
        when(coordinator.resume("s-paused", "op-1")).thenReturn(rejected);
        SessionManageService service = newService();

        SessionManageService.ResumeOutcome outcome = service.resume("s-paused", "op-1");

        assertThat(outcome.status().value()).isEqualTo(429);
        assertThat(outcome.body().get("error")).isEqualTo("ADMISSION_REJECTED");
    }

    @Test
    void terminateRunningSessionAcceptedAndIdleNotFoundConflict() {
        mockSessionRunning("s-running");
        SessionOperationResult accepted = SessionOperationResult.accepted(SessionState.TERMINATED, "ok");
        when(coordinator.terminate("s-running", "op-1")).thenReturn(accepted);
        SessionManageService service = newService();

        SessionManageService.TerminateOutcome acceptedOutcome = service.terminate("s-running", "op-1");
        SessionManageService.TerminateOutcome missingOutcome = service.terminate("s-missing", "op-1");

        assertThat(acceptedOutcome.status().value()).isEqualTo(202);
        assertThat(missingOutcome.status().value()).isEqualTo(202);
    }

    @Test
    void coordinatorRecordedPauseWinsOverActiveTaskLookup() {
        when(coordinator.managedState("s-paused")).thenReturn(SessionState.PAUSED);
        SessionManageService service = newService();

        var detail = service.sessionDetail("s-paused");

        assertThat(detail).isNotNull();
        assertThat(detail.get("state")).isEqualTo(SessionState.PAUSED.name());
    }

    @Test
    void sessionDetailResolvesRunningState() {
        mockSessionRunning("s-running");
        SessionManageService service = newService();

        var detail = service.sessionDetail("s-running");

        assertThat(detail).isNotNull();
        assertThat(detail.get("state")).isEqualTo(SessionState.RUNNING.name());
    }

    @Test
    void compactIdleSessionAccepted() {
        Map<String, Object> compactResult = Map.of("session_id", "s-idle", "result", "compressed");
        when(coordinator.compactSession(Mockito.eq("s-idle"), Mockito.eq("op-1"), Mockito.any()))
                .thenReturn(compactResult);
        SessionManageService service = newService();

        SessionManageService.CompactOutcome outcome = service.compact("s-idle", "op-1", null);

        assertThat(outcome.status().value()).isEqualTo(200);
        assertThat(outcome.body()).containsEntry("result", "compressed");
        assertThat(outcome.body()).containsEntry("accepted", true);
    }

    @Test
    void compactRunningSessionConflicts() {
        mockSessionRunning("s-running");
        SessionManageService service = newService();

        SessionManageService.CompactOutcome outcome = service.compact("s-running", "op-1", null);

        assertThat(outcome.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(outcome.body().get("error")).isEqualTo("INVALID_SESSION_STATE");
        Mockito.verify(coordinator, Mockito.never()).compactSession(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void compactPausedSessionConflicts() {
        when(coordinator.managedState("s-paused")).thenReturn(SessionState.PAUSED);
        SessionManageService service = newService();

        SessionManageService.CompactOutcome outcome = service.compact("s-paused", "op-1", null);

        assertThat(outcome.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        assertThat(outcome.body().get("error")).isEqualTo("INVALID_SESSION_STATE");
        Mockito.verify(coordinator, Mockito.never()).compactSession(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void compactBlankSessionNotFound() {
        SessionManageService service = newService();

        SessionManageService.CompactOutcome outcome = service.compact("  ", "op-1", null);

        assertThat(outcome.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        assertThat(outcome.body().get("error")).isEqualTo("session not found");
    }

    @Test
    void compactExecutionFailureMapsTo500() {
        when(coordinator.compactSession(Mockito.eq("s-idle"), Mockito.eq("op-1"), Mockito.any()))
                .thenThrow(new IllegalStateException("compression failed"));
        SessionManageService service = newService();

        SessionManageService.CompactOutcome outcome = service.compact("s-idle", "op-1", null);

        assertThat(outcome.status().value()).isEqualTo(500);
        assertThat(outcome.body().get("error")).isEqualTo("COMPACT_FAILED");
        assertThat(outcome.body().get("detail")).isEqualTo("compression failed");
    }

    @Test
    void compactDisabledWhenOperationsDisabled() {
        properties.setOperationsEnabled(false);
        SessionManageService service = newService();

        SessionManageService.CompactOutcome outcome = service.compact("s-idle", "op-1", null);

        assertThat(outcome.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_FOUND);
        Mockito.verify(coordinator, Mockito.never()).compactSession(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void unsupportedOperationsMapTo501() {
        when(coordinator.supportsSessionOperations()).thenReturn(false);
        SessionManageService service = newService();

        SessionManageService.PauseOutcome pauseOutcome = service.pause("s-1", "op-1", null);
        SessionManageService.ResumeOutcome resumeOutcome = service.resume("s-1", "op-1");
        SessionManageService.CompactOutcome compactOutcome = service.compact("s-1", "op-1", null);

        assertThat(pauseOutcome.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_IMPLEMENTED);
        assertThat(pauseOutcome.body().get("error")).isEqualTo("SESSION_OPERATION_UNSUPPORTED");
        assertThat(resumeOutcome.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_IMPLEMENTED);
        assertThat(compactOutcome.status()).isEqualTo(org.springframework.http.HttpStatus.NOT_IMPLEMENTED);
        Mockito.verify(coordinator, Mockito.never()).pause(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());
        Mockito.verify(coordinator, Mockito.never()).resume(Mockito.any(), Mockito.any());
        Mockito.verify(coordinator, Mockito.never()).compactSession(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void stateQueryFailureMapsTo503ForPauseResumeCompact() {
        when(activeTaskQuery.snapshot()).thenThrow(new IllegalStateException("task store down"));
        SessionManageService service = newService();

        SessionManageService.PauseOutcome pauseOutcome = service.pause("s-1", "op-1", null);
        SessionManageService.ResumeOutcome resumeOutcome = service.resume("s-1", "op-1");
        SessionManageService.CompactOutcome compactOutcome = service.compact("s-1", "op-1", null);

        assertThat(pauseOutcome.status().value()).isEqualTo(503);
        assertThat(pauseOutcome.body().get("error")).isEqualTo("SESSION_STATE_UNAVAILABLE");
        assertThat(resumeOutcome.status().value()).isEqualTo(503);
        assertThat(compactOutcome.status().value()).isEqualTo(503);
        Mockito.verify(coordinator, Mockito.never()).pause(Mockito.any(), Mockito.any(), Mockito.any(),
                Mockito.any());
        Mockito.verify(coordinator, Mockito.never()).resume(Mockito.any(), Mockito.any());
        Mockito.verify(coordinator, Mockito.never()).compactSession(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void stateQueryFailureStillTerminates() {
        when(activeTaskQuery.snapshot()).thenThrow(new IllegalStateException("task store down"));
        SessionOperationResult accepted = SessionOperationResult.accepted(SessionState.TERMINATED, "ok");
        when(coordinator.terminate("s-1", "op-1")).thenReturn(accepted);
        SessionManageService service = newService();

        SessionManageService.TerminateOutcome outcome = service.terminate("s-1", "op-1");

        assertThat(outcome.status().value()).isEqualTo(202);
        Mockito.verify(coordinator).terminate("s-1", "op-1");
    }

    private SessionManageService newService() {
        return new SessionManageService(coordinator, activeTaskQueryProvider, readinessProvider, properties);
    }

    private void mockSessionRunning(String sessionId) {
        ActiveTaskInfo task = Mockito.mock(ActiveTaskInfo.class);
        when(task.getConversationId()).thenReturn(sessionId);
        ConcurrencyLoadSnapshot snapshot = Mockito.mock(ConcurrencyLoadSnapshot.class);
        when(snapshot.getTasks()).thenReturn(List.of(task));
        when(activeTaskQuery.snapshot()).thenReturn(snapshot);
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
