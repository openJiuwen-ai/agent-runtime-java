/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link A2AAgentExecutor}.
 */
class A2AAgentExecutorTest {
    @Test
    void syncInterruptStoresRawDataUnderReservedMetadataKey() {
        Map<String, Object> interaction = Map.of(
            "type", "__interaction__",
            "index", 0,
            "payload", Map.of(
                "kind", "confirmation",
                "items", List.of(Map.of("name", "transfer", "type", "tool_call"))),
            "message", "Approve transfer?",
            "context", Map.of("_interrupt_kind", "ask_user"));
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(
            new QueryResponse(Map.of("_interrupt", interaction), "ctx-1"));
        A2AProtocolAdapter adapter = requestAdapter(false);
        CapturingEventQueue queue = new CapturingEventQueue();

        RequestContext context = requestContext("task-1", "ctx-1", false);

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        assertThat(inputRequiredMessage(queue).metadata()).containsOnly(Map.entry("_interrupt", interaction));
    }

    @Test
    void streamingInterruptStoresRawDataUnderReservedMetadataKey() {
        Map<String, Object> interaction = Map.of(
            "type", "__interaction__",
            "index", 0,
            "payload", Map.of(
                "kind", "confirmation",
                "items", List.of(Map.of("name", "transfer", "type", "tool_call"))),
            "message", "Approve transfer?",
            "context", Map.of("_interrupt_kind", "ask_user"));
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, interaction));
            observer.onComplete();
            return null;
        }).when(orchestrator).streamQuery(any(), any());
        A2AProtocolAdapter adapter = requestAdapter(true);
        CapturingEventQueue queue = new CapturingEventQueue();
        RequestContext context = requestContext("task-1", "ctx-1", true);

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        assertThat(inputRequiredMessage(queue).metadata()).containsOnly(Map.entry("_interrupt", interaction));
    }

    @Test
    void syncCompletedPathWaitsForEnqueuedFinalEventToDrain() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        A2AProtocolAdapter adapter = mock(A2AProtocolAdapter.class);
        ServeRequest request = new ServeRequest();
        request.setConversationId("ctx-1");
        request.setStream(false);
        when(adapter.toServeRequest(any())).thenReturn(request);

        A2AAgentExecutor executor = new A2AAgentExecutor(orchestrator, adapter);
        CountingEventQueue queue = new CountingEventQueue();
        RequestContext context = requestContext("task-1", "ctx-1", false);
        AgentEmitter emitter = new AgentEmitter(context, queue);

        executor.execute(context, emitter);

        assertThat(queue.sizeCalls.get()).isPositive();
    }

    @Test
    void copiesOnlyStoredInterruptOntoResumeRequest() {
        Map<String, Object> interaction = Map.of("kind", "message", "message", "Continue");
        Message statusMessage = Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart("Continue")))
            .metadata(Map.of("_interrupt", interaction, "trace", "not-resume-data"))
            .build();
        Task task = Task.builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, statusMessage, null))
            .build();

        assertStoredInterruptCopied(task, interaction);
    }

    @Test
    void copiesStoredInterruptFromHistoryWhenStatusMessageIsMissing() {
        Map<String, Object> interaction = Map.of("kind", "confirmation", "message", "Approve");
        Message inputRequiredMessage = Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart("Approve")))
            .metadata(Map.of("_interrupt", interaction, "trace", "not-resume-data"))
            .build();
        Message resumeMessage = Message.builder()
            .role(Message.Role.ROLE_USER)
            .parts(List.of(new TextPart("APPROVE")))
            .build();
        Task task = Task.builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
            .history(List.of(inputRequiredMessage, resumeMessage))
            .build();

        assertStoredInterruptCopied(task, interaction);
    }

    @Test
    void doesNotCopyStoredInterruptFromNonInputRequiredTask() {
        Map<String, Object> interaction = Map.of("kind", "message", "message", "Old interrupt");
        Message oldInterrupt = Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart("Old interrupt")))
            .metadata(Map.of("_interrupt", interaction))
            .build();
        Task task = Task.builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
            .history(List.of(oldInterrupt))
            .build();

        AgentEmitter emitter = mock(AgentEmitter.class);
        ServeRequest request = executeWithStoredTask(task, emitter);

        assertThat(request.getMetadata()).doesNotContainKey("_interrupt");
        verify(emitter, never()).submit();
    }

    private static void assertStoredInterruptCopied(Task task, Map<String, Object> interaction) {
        ServeRequest request = executeWithStoredTask(task, mock(AgentEmitter.class));

        assertThat(request.getMetadata())
            .containsEntry("_interrupt", interaction)
            .doesNotContainKey("trace");
    }

    private static ServeRequest executeWithStoredTask(Task task, AgentEmitter emitter) {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        A2AProtocolAdapter adapter = requestAdapter(false);
        RequestContext context = requestContext("task-1", "ctx-1", false);
        when(context.getTask()).thenReturn(task);

        new A2AAgentExecutor(orchestrator, adapter).execute(context, emitter);

        org.mockito.ArgumentCaptor<ServeRequest> request = org.mockito.ArgumentCaptor.forClass(ServeRequest.class);
        verify(orchestrator).query(request.capture());
        return request.getValue();
    }

    private static RequestContext requestContext(String taskId, String contextId, boolean isStream) {
        RequestContext context = mock(RequestContext.class);
        when(context.getTaskId()).thenReturn(taskId);
        when(context.getContextId()).thenReturn(contextId);
        when(context.getMetadata()).thenReturn(Map.of());
        ServerCallContext callContext = mock(ServerCallContext.class);
        when(callContext.getState()).thenReturn(new java.util.HashMap<>(Map.of("_a2a_stream", isStream)));
        when(context.getCallContext()).thenReturn(callContext);
        return context;
    }

    private static A2AProtocolAdapter requestAdapter(boolean isStream) {
        A2AProtocolAdapter adapter = mock(A2AProtocolAdapter.class);
        ServeRequest request = new ServeRequest();
        request.setConversationId("ctx-1");
        request.setStream(isStream);
        when(adapter.toServeRequest(any())).thenReturn(request);
        return adapter;
    }

    private static org.a2aproject.sdk.spec.Message inputRequiredMessage(CapturingEventQueue queue) {
        return queue.events.stream()
            .filter(TaskStatusUpdateEvent.class::isInstance)
            .map(TaskStatusUpdateEvent.class::cast)
            .map(TaskStatusUpdateEvent::status)
            .map(status -> status.message())
            .filter(java.util.Objects::nonNull)
            .findFirst()
            .orElseThrow();
    }

    private static final class CapturingEventQueue extends CountingEventQueue {
        private final List<org.a2aproject.sdk.spec.Event> events = new ArrayList<>();

        @Override
        public void enqueueItem(EventQueueItem item) {
            events.add(item.getEvent());
        }
    }

    private static class CountingEventQueue extends EventQueue {
        private final AtomicInteger sizeCalls = new AtomicInteger();

        @Override
        public void awaitQueuePollerStart() {
        }

        @Override
        public void signalQueuePollerStarted() {
        }

        @Override
        public void enqueueItem(EventQueueItem item) {
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
