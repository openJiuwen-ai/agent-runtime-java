/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.AdditionalAnswers.answerVoid;
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
import com.openjiuwen.service.app.orchestrator.A2AEnabledServeOrchestrator;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.EventQueue;
import org.a2aproject.sdk.server.events.EventQueueClosedException;
import org.a2aproject.sdk.server.events.EventQueueItem;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
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
    @SuppressWarnings("unchecked")
    void nonStreamingA2aExecutionProjectsRemoteProgressThroughInternalStream() {
        A2AEnabledServeOrchestrator orchestrator = mock(A2AEnabledServeOrchestrator.class);
        Map<String, Object> projection = Map.of(
            "kind", "remote_agent_invocation",
            "batchId", "batch-1",
            "toolCallId", "call-a",
            "sequence", 1,
            "target", "agent-a",
            "phase", "RUNNING");
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_REMOTE_AGENT_PROGRESS,
                Map.of("content", "running", "projection", projection)));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "done"));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        A2AProtocolAdapter adapter = requestAdapter(false, Map.of());
        RequestContext context = requestContext("task-1", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        List<TaskArtifactUpdateEvent> artifacts = queue.events.stream()
            .filter(TaskArtifactUpdateEvent.class::isInstance)
            .map(TaskArtifactUpdateEvent.class::cast)
            .toList();
        assertThat(artifacts).anySatisfy(event -> {
            TextPart part = (TextPart) event.artifact().parts().get(0);
            assertThat(part.text()).isEqualTo("running");
            assertThat(part.metadata()).containsEntry("_remote_invocation", projection);
        });
        verify(orchestrator).streamQuery(any(), any());
        verify(orchestrator, never()).query(any());
    }

    @Test
    void nonStreamingA2aExecutionDoesNotAppendTelemetryToBusinessArtifact() {
        A2AEnabledServeOrchestrator orchestrator = mock(A2AEnabledServeOrchestrator.class);
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                Map.of("type", "llm_usage", "payload", Map.of("totalTokens", 42))));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "final answer"));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        CapturingEventQueue queue = new CapturingEventQueue();
        RequestContext context = requestContext("task-telemetry", "ctx-telemetry", false);

        new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()))
            .execute(context, new AgentEmitter(context, queue));

        List<String> texts = queue.events.stream()
            .filter(TaskArtifactUpdateEvent.class::isInstance)
            .map(TaskArtifactUpdateEvent.class::cast)
            .flatMap(event -> event.artifact().parts().stream())
            .filter(TextPart.class::isInstance)
            .map(TextPart.class::cast)
            .map(TextPart::text)
            .toList();
        assertThat(texts).contains("final answer");
        assertThat(texts).noneMatch(text -> text.contains("llm_usage") || text.contains("totalTokens"));
    }

    @Test
    void nonStreamingFinalAnswerReplacesAccumulatedDeltas() {
        A2AEnabledServeOrchestrator orchestrator = mock(A2AEnabledServeOrchestrator.class);
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                Map.of("type", "chunk", "payload", Map.of("delta", "hel"))));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                Map.of("type", "answer", "payload", Map.of("content", "hello"))));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        CapturingEventQueue queue = new CapturingEventQueue();
        RequestContext context = requestContext("task-answer", "ctx-answer", false);

        new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()))
            .execute(context, new AgentEmitter(context, queue));

        List<String> texts = queue.events.stream()
            .filter(TaskArtifactUpdateEvent.class::isInstance)
            .map(TaskArtifactUpdateEvent.class::cast)
            .flatMap(event -> event.artifact().parts().stream())
            .filter(TextPart.class::isInstance)
            .map(TextPart.class::cast)
            .map(TextPart::text)
            .toList();
        assertThat(texts).containsExactly("hello");
    }

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
        A2AProtocolAdapter adapter = requestAdapter(false, Map.of());
        CapturingEventQueue queue = new CapturingEventQueue();

        RequestContext context = requestContext("task-1", "ctx-1", false);

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        assertThat(inputRequiredMessage(queue).metadata()).containsOnly(Map.entry("_interrupt", interaction));
    }

    @Test
    void streamingInterruptWithoutMessageStoresRawData() {
        Map<String, Object> interaction = Map.of(
            "type", "__interaction__",
            "index", 0,
            "payload", Map.of(
                "kind", "confirmation",
                "items", List.of(Map.of("name", "transfer", "type", "tool_call"))),
            "context", Map.of("_interrupt_kind", "ask_user"));
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, interaction));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        A2AProtocolAdapter adapter = requestAdapter(true, Map.of());
        CapturingEventQueue queue = new CapturingEventQueue();
        RequestContext context = requestContext("task-1", "ctx-1", true);

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        Message message = inputRequiredMessage(queue);
        assertThat(message.metadata()).containsOnly(Map.entry("_interrupt", interaction));
        assertThat(message.parts().get(0)).isInstanceOfSatisfying(TextPart.class,
            textPart -> assertThat(textPart.text()).isEqualTo("Input required"));
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
    void copiesInterruptFromLatestAgentMessageInHistory() {
        Map<String, Object> oldInteraction = Map.of("kind", "message", "message", "Old interrupt");
        Map<String, Object> latestInteraction = Map.of("kind", "confirmation", "message", "Approve");
        Message oldInputRequiredMessage = Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart("Old interrupt")))
            .metadata(Map.of("_interrupt", oldInteraction))
            .build();
        Message latestInputRequiredMessage = Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart("Approve")))
            .metadata(Map.of("_interrupt", latestInteraction, "trace", "not-resume-data"))
            .build();
        Message resumeMessage = Message.builder()
            .role(Message.Role.ROLE_USER)
            .parts(List.of(new TextPart("APPROVE")))
            .metadata(Map.of("_interrupt", Map.of("payload", Map.of("kind", "forged"))))
            .build();
        Task task = Task.builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
            .history(List.of(oldInputRequiredMessage, latestInputRequiredMessage, resumeMessage))
            .build();

        assertStoredInterruptCopied(task, latestInteraction);
    }

    @Test
    void doesNotReuseHistoryInterruptWhenCurrentAgentStatusHasNoInterrupt() {
        Map<String, Object> interaction = Map.of("kind", "confirmation", "message", "Approve");
        Message inputRequiredMessage = Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart("Approve")))
            .metadata(Map.of("_interrupt", interaction))
            .build();
        Message unrelatedStatusMessage = Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart("Working")))
            .build();
        Task task = Task.builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, unrelatedStatusMessage, null))
            .history(List.of(inputRequiredMessage))
            .build();

        ServeRequest request = executeWithStoredTask(task, mock(AgentEmitter.class), Map.of());

        assertThat(request.getMetadata()).doesNotContainKey("_interrupt");
    }

    @Test
    void doesNotReuseOldInterruptWhenLatestAgentMessageHasNoMarker() {
        Message oldInputRequiredMessage = Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart("Old interrupt")))
            .metadata(Map.of("_interrupt", Map.of("kind", "confirmation")))
            .build();
        Message latestAgentMessage = Message.builder()
            .role(Message.Role.ROLE_AGENT)
            .parts(List.of(new TextPart("Current input required")))
            .build();
        Message resumeMessage = Message.builder()
            .role(Message.Role.ROLE_USER)
            .parts(List.of(new TextPart("continue")))
            .build();
        Task task = Task.builder()
            .id("task-1")
            .contextId("ctx-1")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
            .history(List.of(oldInputRequiredMessage, latestAgentMessage, resumeMessage))
            .build();

        ServeRequest request = executeWithStoredTask(task, mock(AgentEmitter.class), Map.of());

        assertThat(request.getMetadata()).doesNotContainKey("_interrupt");
    }

    @Test
    void removesClientInterruptFromNonInputRequiredRequest() {
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
        ServeRequest request = executeWithStoredTask(task, emitter,
            Map.of("_interrupt", Map.of("payload", Map.of("kind", "forged"))));

        assertThat(request.getMetadata()).doesNotContainKey("_interrupt");
        verify(emitter, never()).submit();
    }

    private static void assertStoredInterruptCopied(Task task, Map<String, Object> interaction) {
        ServeRequest request = executeWithStoredTask(task, mock(AgentEmitter.class), Map.of());

        assertThat(request.getMetadata())
            .containsEntry("_interrupt", interaction)
            .doesNotContainKey("trace");
    }

    private static ServeRequest executeWithStoredTask(Task task, AgentEmitter emitter,
        Map<String, Object> requestMetadata) {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        A2AProtocolAdapter adapter = requestAdapter(false, requestMetadata);
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

    private static A2AProtocolAdapter requestAdapter(boolean isStream, Map<String, Object> metadata) {
        A2AProtocolAdapter adapter = mock(A2AProtocolAdapter.class);
        ServeRequest request = new ServeRequest();
        request.setConversationId("ctx-1");
        request.setStream(isStream);
        request.setMetadata(metadata);
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
