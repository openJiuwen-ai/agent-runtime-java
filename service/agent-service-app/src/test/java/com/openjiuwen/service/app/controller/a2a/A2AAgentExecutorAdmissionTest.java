/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionListener;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionService;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.EventQueue;
import org.a2aproject.sdk.server.events.EventQueueClosedException;
import org.a2aproject.sdk.server.events.EventQueueItem;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Event;
import org.a2aproject.sdk.server.events.MainEventBus;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Admission-flow unit tests for {@link A2AAgentExecutor} under the
 * consequence-landed release model — DFX-006 test plan U-09..U-12.
 */
class A2AAgentExecutorAdmissionTest {
    /** TTL disabled: leases stay pending until consequenceLanded, keeping attribution deterministic. */
    private static final AdmissionReleaseCoordinator.Timing NO_TTL =
            new AdmissionReleaseCoordinator.Timing(-1L, 50L, 250L, 80L);

    @Test
    void stalledEventProcessorRejectsBeforeQuotaWithStateUnavailable() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        MainEventBus bus = mock(MainEventBus.class);
        when(bus.size()).thenReturn(1);
        TaskAdmissionGate gate = gate(30);
        when(gate.tryAcquire()).thenReturn(true);
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate, bus, null, null,
                new AdmissionReleaseCoordinator.Timing(-1L, 50L, 250L, -1L));
        A2AAgentExecutor executor = new A2AAgentExecutor(orchestrator, requestAdapter(false), gate, null,
                coordinator);

        A2AError error = catchThrowableOfType(
                () -> executor.execute(requestContext("task-1", "ctx-1", false), mock(AgentEmitter.class)),
                A2AError.class);

        assertThat(error).isNotNull();
        assertThat(error.getMessage()).isEqualTo("Agent Runtime is temporarily unavailable");
        assertThat(error.getDetails()).containsEntry("businessCode",
                TaskAdmissionService.BUSINESS_CODE_EVENT_QUEUE_STATE_UNAVAILABLE);
        verify(gate, never()).tryAcquire();
        verify(orchestrator, never()).query(any());
        verify(orchestrator, never()).streamQuery(any(), any());
    }

    @Test
    void quotaExhaustionWithNoPendingReleaseClassifiesConcurrencyLimit() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        TaskAdmissionGate gate = gate(30);
        when(gate.tryAcquire()).thenReturn(false);
        AdmissionReleaseCoordinator coordinator = coordinator(gate, 0);
        A2AAgentExecutor executor = new A2AAgentExecutor(orchestrator, requestAdapter(false), gate, null,
                coordinator);

        A2AError error = catchThrowableOfType(
                () -> executor.execute(requestContext("task-1", "ctx-1", false), mock(AgentEmitter.class)),
                A2AError.class);

        assertThat(error.getDetails()).containsEntry("businessCode",
                TaskAdmissionService.BUSINESS_CODE_CONCURRENCY_LIMIT_REACHED);
        verify(gate, never()).release();
        verify(orchestrator, never()).query(any());
    }

    @Test
    void quotaExhaustionWithPendingReleaseClassifiesEventQueueOverloaded() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        TaskAdmissionGate gate = gate(30);
        when(gate.tryAcquire()).thenReturn(false);
        AdmissionReleaseCoordinator coordinator = coordinator(gate, 1);
        A2AAgentExecutor executor = new A2AAgentExecutor(orchestrator, requestAdapter(false), gate, null,
                coordinator);

        A2AError error = catchThrowableOfType(
                () -> executor.execute(requestContext("task-1", "ctx-1", false), mock(AgentEmitter.class)),
                A2AError.class);

        assertThat(error.getDetails()).containsEntry("businessCode",
                TaskAdmissionService.BUSINESS_CODE_EVENT_QUEUE_OVERLOADED);
        verify(gate, never()).release();
    }

    @Test
    void preAcquiredPermitSkipsClassificationAndAcquireButRegistersRound() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        TaskAdmissionGate gate = gate(30);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        AdmissionReleaseCoordinator coordinator = coordinator(gate, 0, listener);
        A2AAgentExecutor executor = new A2AAgentExecutor(orchestrator, requestAdapter(false), gate, listener,
                coordinator);
        RequestContext context = requestContext("task-1", "ctx-1", false,
                Map.of(TaskAdmissionService.HANDOVER_MARKER_KEY, Boolean.TRUE));

        executor.execute(context, new AgentEmitter(context, new CapturingEventQueue()));

        verify(gate, never()).tryAcquire();
        verify(listener).onAdmitted("task-1", "ctx-1");
        assertThat(coordinator.pendingReleaseCount()).isEqualTo(1);
        verify(gate, never()).release();
    }

    @Test
    void completeRoundMarksPendingReleaseInsteadOfImmediateRelease() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        TaskAdmissionGate gate = gate(30);
        when(gate.tryAcquire()).thenReturn(true);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        AdmissionReleaseCoordinator coordinator = coordinator(gate, 0, listener);
        A2AAgentExecutor executor = new A2AAgentExecutor(orchestrator, requestAdapter(false), gate, listener,
                coordinator);
        RequestContext context = requestContext("task-1", "ctx-1", false);

        executor.execute(context, new AgentEmitter(context, new CapturingEventQueue()));

        verify(gate, never()).release();
        assertThat(coordinator.pendingReleaseCount()).isEqualTo(1);

        coordinator.consequenceLanded("task-1");
        verify(gate, times(1)).release();
        verify(listener).onReleased("task-1", "ctx-1");
        assertThat(coordinator.pendingReleaseCount()).isZero();
    }

    @Test
    void completeRoundStillMarksPendingReleaseOnAgentFailure() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenThrow(new IllegalStateException("agent failed"));
        TaskAdmissionGate gate = gate(30);
        when(gate.tryAcquire()).thenReturn(true);
        AdmissionReleaseCoordinator coordinator = coordinator(gate, 0);
        A2AAgentExecutor executor = new A2AAgentExecutor(orchestrator, requestAdapter(false), gate, null,
                coordinator);
        RequestContext context = requestContext("task-1", "ctx-1", false);

        executor.execute(context, new AgentEmitter(context, new CapturingEventQueue()));

        assertThat(coordinator.pendingReleaseCount()).isEqualTo(1);
        verify(gate, never()).release();

        coordinator.consequenceLanded("task-1");
        verify(gate, times(1)).release();
    }

    @Test
    void newTaskAndContinuationShareTheSameAdmissionPath() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        TaskAdmissionGate gate = gate(30);
        when(gate.tryAcquire()).thenReturn(true);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        AdmissionReleaseCoordinator coordinator = coordinator(gate, 0, listener);
        A2AAgentExecutor executor = new A2AAgentExecutor(orchestrator, requestAdapter(false), gate, listener,
                coordinator);
        RequestContext context = requestContext("task-1", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        executor.execute(context, new AgentEmitter(context, queue));

        ServeRequest continuation = new ServeRequest();
        continuation.setConversationId("ctx-1");
        continuation.setStream(false);
        continuation.setMetadata(Map.of());
        executor.continueTask(context, continuation, new AgentEmitter(context, queue));

        verify(listener, times(2)).onAdmitted("task-1", "ctx-1");
        assertThat(coordinator.pendingReleaseCount()).isEqualTo(2);
        verify(gate, never()).release();
    }

    private static AdmissionReleaseCoordinator coordinator(TaskAdmissionGate gate, int pendingRounds) {
        return coordinator(gate, pendingRounds, null);
    }

    private static AdmissionReleaseCoordinator coordinator(TaskAdmissionGate gate, int pendingRounds,
            TaskAdmissionListener listener) {
        AdmissionReleaseCoordinator coordinator = new AdmissionReleaseCoordinator(gate, mock(MainEventBus.class),
                null, listener, NO_TTL);
        for (int index = 0; index < pendingRounds; index++) {
            coordinator.register("other-task-" + index, "other-conv").ifPresent(coordinator::completeRound);
        }
        return coordinator;
    }

    private static TaskAdmissionGate gate(int limit) {
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.limit()).thenReturn(limit);
        return gate;
    }

    private static A2AProtocolAdapter requestAdapter(boolean isStream) {
        A2AProtocolAdapter adapter = mock(A2AProtocolAdapter.class);
        ServeRequest request = new ServeRequest();
        request.setConversationId("ctx-1");
        request.setStream(isStream);
        request.setMetadata(Map.of());
        when(adapter.toServeRequest(any())).thenReturn(request);
        return adapter;
    }

    private static RequestContext requestContext(String taskId, String contextId, boolean isStream) {
        return requestContext(taskId, contextId, isStream, Map.of());
    }

    private static RequestContext requestContext(String taskId, String contextId, boolean isStream,
            Map<String, Object> extraState) {
        RequestContext context = mock(RequestContext.class);
        when(context.getTaskId()).thenReturn(taskId);
        when(context.getContextId()).thenReturn(contextId);
        when(context.getMetadata()).thenReturn(Map.of());
        ServerCallContext callContext = mock(ServerCallContext.class);
        Map<String, Object> state = new HashMap<>(Map.of("_a2a_stream", isStream));
        state.putAll(extraState);
        when(callContext.getState()).thenReturn(state);
        when(context.getCallContext()).thenReturn(callContext);
        return context;
    }

    private static final class CapturingEventQueue extends EventQueue {
        private final List<Event> events = new ArrayList<>();

        private final AtomicInteger sizeCalls = new AtomicInteger();

        @Override
        public void awaitQueuePollerStart() {
        }

        @Override
        public void signalQueuePollerStarted() {
        }

        @Override
        public void enqueueItem(EventQueueItem item) {
            events.add(item.getEvent());
        }

        @Override
        public EventQueue tap() {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public EventQueueItem dequeueEventItem(int waitMilliSeconds) throws EventQueueClosedException {
            return null;
        }

        @Override
        public int size() {
            sizeCalls.incrementAndGet();
            return 0;
        }

        @Override
        public void close() {
        }

        @Override
        public void close(boolean isImmediate) {
        }

        @Override
        public void close(boolean isImmediate, boolean shouldNotifyParent) {
        }
    }
}
