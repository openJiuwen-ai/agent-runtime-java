/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.EventQueue;
import org.a2aproject.sdk.server.events.EventQueueClosedException;
import org.a2aproject.sdk.server.events.EventQueueItem;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Event;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Tests for the generic cancel signal an execution side may raise (DFX-003).
 */
class A2AAgentExecutorCancelTest {
    @Test
    void theCancelChunkTypeIsDistinctFromEveryOtherType() {
        assertThat(QueryChunk.TYPE_CANCEL).isEqualTo("cancel");
        assertThat(QueryChunk.TYPE_CANCEL).isNotEqualTo(QueryChunk.TYPE_CHUNK)
                .isNotEqualTo(QueryChunk.TYPE_INTERRUPT).isNotEqualTo(QueryChunk.TYPE_ERROR)
                .isNotEqualTo(QueryChunk.TYPE_REMOTE_AGENT_OUTPUT);
    }

    @Test
    void aStreamingCancelChunkMovesTheTaskToTheCanceledState() {
        CapturingQueue queue = new CapturingQueue();
        ServeOrchestrator orchestrator = streamingOrchestrator(observer -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CANCEL, Map.of("reason", "user cancelled")));
            observer.onComplete();
        });

        execute(orchestrator, queue, true);

        assertThat(terminalStates(queue)).containsExactly(TaskState.TASK_STATE_CANCELED);
        verify(orchestrator).cancelActive(eq("ctx-1"));
    }

    @Test
    void aNonStreamingCancelResultMovesTheTaskToTheCanceledState() {
        CapturingQueue queue = new CapturingQueue();
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any()))
                .thenReturn(new QueryResponse(Map.of("_cancel", Map.of("reason", "policy")), "ctx-1"));

        execute(orchestrator, queue, false);

        assertThat(terminalStates(queue)).containsExactly(TaskState.TASK_STATE_CANCELED);
        verify(orchestrator).cancelActive(eq("ctx-1"));
    }

    @Test
    void aCancelResultIsJudgedBeforeAnInterruptResult() {
        CapturingQueue queue = new CapturingQueue();
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(
                Map.of("_cancel", Map.of("reason", "policy"), "_interrupt", Map.of("message", "confirm?")), "ctx-1"));

        execute(orchestrator, queue, false);

        assertThat(terminalStates(queue)).containsExactly(TaskState.TASK_STATE_CANCELED);
        assertThat(allStates(queue)).doesNotContain(TaskState.TASK_STATE_INPUT_REQUIRED);
    }

    @Test
    void outputThatFollowsACancelCannotChangeTheVerdict() {
        CapturingQueue queue = new CapturingQueue();
        ServeOrchestrator orchestrator = streamingOrchestrator(observer -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CANCEL, "user cancelled"));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "more text"));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", "confirm?")));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR, "boom"));
            observer.onComplete();
        });

        execute(orchestrator, queue, true);

        // 取消是唯一终局：后续的普通、中断、错误分片都不得改判
        assertThat(terminalStates(queue)).containsExactly(TaskState.TASK_STATE_CANCELED);
        assertThat(allStates(queue)).doesNotContain(TaskState.TASK_STATE_INPUT_REQUIRED,
                TaskState.TASK_STATE_FAILED, TaskState.TASK_STATE_COMPLETED);
    }

    @Test
    void repeatingTheCancelWithinOneExecutionStaysIdempotent() {
        CapturingQueue queue = new CapturingQueue();
        ServeOrchestrator orchestrator = streamingOrchestrator(observer -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CANCEL, "first"));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CANCEL, "second"));
            observer.onComplete();
        });

        execute(orchestrator, queue, true);

        assertThat(terminalStates(queue)).containsExactly(TaskState.TASK_STATE_CANCELED);
        verify(orchestrator).cancelActive(eq("ctx-1"));
    }

    @Test
    void anOrdinaryStreamStillCompletesWhenNoCancelArrives() {
        CapturingQueue queue = new CapturingQueue();
        ServeOrchestrator orchestrator = streamingOrchestrator(observer -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "text"));
            observer.onComplete();
        });

        execute(orchestrator, queue, true);

        assertThat(allStates(queue)).doesNotContain(TaskState.TASK_STATE_CANCELED);
        verify(orchestrator, never()).cancelActive(any());
    }

    @Test
    void theReasonIsReadFromTextAndFromAReasonFieldAndIsOptional() throws Exception {
        Method cancelReason = A2AAgentExecutor.class.getDeclaredMethod("cancelReason", Object.class);
        cancelReason.setAccessible(true);

        assertThat(cancelReason.invoke(null, "user cancelled")).isEqualTo("user cancelled");
        assertThat(cancelReason.invoke(null, Map.of("reason", "policy"))).isEqualTo("policy");
        assertThat(cancelReason.invoke(null, (Object) null)).isEqualTo("");
        assertThat(cancelReason.invoke(null, Map.of("other", "x"))).isEqualTo("");
        assertThat(cancelReason.invoke(null, 42)).isEqualTo("");
    }

    private static void execute(ServeOrchestrator orchestrator, CapturingQueue queue, boolean isStream) {
        RequestContext context = requestContext(isStream);
        new A2AAgentExecutor(orchestrator, adapter(isStream)).execute(context, new AgentEmitter(context, queue));
    }

    private static ServeOrchestrator streamingOrchestrator(java.util.function.Consumer<QueryStreamObserver> script) {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> script.accept(observer)))
                .when(orchestrator).streamQuery(any(), any());
        return orchestrator;
    }

    private static List<TaskState> allStates(CapturingQueue queue) {
        return queue.events.stream().filter(TaskStatusUpdateEvent.class::isInstance)
                .map(TaskStatusUpdateEvent.class::cast).map(event -> event.status().state()).toList();
    }

    private static List<TaskState> terminalStates(CapturingQueue queue) {
        return allStates(queue).stream().filter(state -> state == TaskState.TASK_STATE_CANCELED
                || state == TaskState.TASK_STATE_COMPLETED || state == TaskState.TASK_STATE_FAILED).toList();
    }

    private static RequestContext requestContext(boolean isStream) {
        RequestContext context = mock(RequestContext.class);
        when(context.getTaskId()).thenReturn("task-1");
        when(context.getContextId()).thenReturn("ctx-1");
        when(context.getMetadata()).thenReturn(Map.of());
        ServerCallContext callContext = mock(ServerCallContext.class);
        when(callContext.getState()).thenReturn(new java.util.HashMap<>(Map.of("_a2a_stream", isStream)));
        when(context.getCallContext()).thenReturn(callContext);
        return context;
    }

    private static A2AProtocolAdapter adapter(boolean isStream) {
        A2AProtocolAdapter adapter = mock(A2AProtocolAdapter.class);
        ServeRequest request = new ServeRequest();
        request.setConversationId("ctx-1");
        request.setStream(isStream);
        request.setMetadata(Map.of());
        when(adapter.toServeRequest(any())).thenReturn(request);
        return adapter;
    }

    private static final class CapturingQueue extends EventQueue {
        private final List<Event> events = new ArrayList<>();

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
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public int size() {
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
