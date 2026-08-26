/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.AdditionalAnswers.answerVoid;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.concurrency.TaskAdmissionGate;
import com.openjiuwen.service.spec.concurrency.TaskAdmissionListener;
import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.exception.AgentExecutionException;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.EventQueue;
import org.a2aproject.sdk.server.events.EventQueueClosedException;
import org.a2aproject.sdk.server.events.EventQueueItem;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskArtifactUpdateEvent;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TaskStatusUpdateEvent;
import org.a2aproject.sdk.spec.TextPart;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link A2AAgentExecutor}.
 */
class A2AAgentExecutorTest {
    @Test
    void blockingCallContextOverridesAdapterDefaultStreamFlag() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        A2AProtocolAdapter adapter = requestAdapter(true, Map.of());
        RequestContext context = requestContext("task-1", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        org.mockito.ArgumentCaptor<ServeRequest> request = org.mockito.ArgumentCaptor.forClass(ServeRequest.class);
        verify(orchestrator).query(request.capture());
        verify(orchestrator, never()).streamQuery(any(), any());
        assertThat(request.getValue().isStream()).isFalse();
    }

    @Test
    void streamingCallContextOverridesAdapterDefaultStreamFlag() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> observer.onComplete()))
                .when(orchestrator).streamQuery(any(), any());
        A2AProtocolAdapter adapter = requestAdapter(false, Map.of());
        RequestContext context = requestContext("task-1", "ctx-1", true);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        org.mockito.ArgumentCaptor<ServeRequest> request = org.mockito.ArgumentCaptor.forClass(ServeRequest.class);
        verify(orchestrator).streamQuery(request.capture(), any());
        verify(orchestrator, never()).query(any());
        assertThat(request.getValue().isStream()).isTrue();
    }

    @Test
    void nonStreamingA2aReturnsOnlyFinalQueryArtifact() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        A2AProtocolAdapter adapter = requestAdapter(false, Map.of());
        RequestContext context = requestContext("task-1", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        List<TaskArtifactUpdateEvent> artifacts = queue.events.stream()
                .filter(TaskArtifactUpdateEvent.class::isInstance).map(TaskArtifactUpdateEvent.class::cast).toList();
        List<String> texts = artifacts.stream().flatMap(event -> event.artifact().parts().stream())
                .filter(TextPart.class::isInstance).map(TextPart.class::cast).map(TextPart::text).toList();
        assertThat(texts).containsExactly("done");
        assertThat(artifacts).singleElement().satisfies(event -> assertThat(event.artifact().metadata())
                .containsEntry(A2aPartContent.TERMINAL_RESULT_METADATA, true));
        verify(orchestrator).query(any());
        verify(orchestrator, never()).streamQuery(any(), any());
    }

    @Test
    void streamingTerminalArtifactRetainsTerminalProvenance() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    Map.of("type", "llm_output", "index", 0, "payload", Map.of("content", "working"))));
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    Map.of("type", "answer", "index", 1, "payload", Map.of("output", "done"))));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        A2AProtocolAdapter adapter = requestAdapter(true, Map.of());
        RequestContext context = requestContext("task-1", "ctx-1", true);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        List<TaskArtifactUpdateEvent> artifacts = queue.events.stream()
                .filter(TaskArtifactUpdateEvent.class::isInstance).map(TaskArtifactUpdateEvent.class::cast).toList();
        assertThat(artifacts).hasSize(2);
        assertThat(artifacts.get(0).artifact().metadata()).isNullOrEmpty();
        assertThat(artifacts.get(1).artifact().metadata())
                .containsEntry(A2aPartContent.TERMINAL_RESULT_METADATA, true)
                .doesNotContainKey("agentEvent");
    }

    @Test
    void remoteArtifactReparentingPreservesArtifactAndEventFields() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        Artifact artifact = Artifact.builder().artifactId("remote-artifact").name("original-name")
                .description("original-description").parts(new TextPart("remote-output"))
                .metadata(Map.of("business", "kept")).build();
        Map<String, Object> eventMetadata = Map.of("trace", "remote-event");
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_REMOTE_AGENT_OUTPUT,
                    new TaskArtifactUpdateEvent("remote-task", artifact, "remote-context", true, false,
                            eventMetadata)));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        RequestContext context = requestContext("parent-task", "parent-context", true);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, requestAdapter(true, Map.of()))
                .execute(context, new AgentEmitter(context, queue));

        List<TaskArtifactUpdateEvent> artifacts = queue.events.stream()
                .filter(TaskArtifactUpdateEvent.class::isInstance).map(TaskArtifactUpdateEvent.class::cast).toList();
        assertThat(artifacts).singleElement().satisfies(event -> {
            assertThat(event.taskId()).isEqualTo("parent-task");
            assertThat(event.contextId()).isEqualTo("parent-context");
            assertThat(event.artifact()).isSameAs(artifact);
            assertThat(event.append()).isTrue();
            assertThat(event.lastChunk()).isFalse();
            assertThat(event.metadata()).isEqualTo(eventMetadata);
        });
    }

    @Test
    void syncInterruptStoresRawDataUnderReservedMetadataKey() {
        Map<String, Object> interaction = Map.of("type", "__interaction__", "index", 0, "payload",
                Map.of("kind", "confirmation", "items", List.of(Map.of("name", "transfer", "type", "tool_call"))),
                "message", "Approve transfer?", "context", Map.of("_interrupt_kind", "ask_user"));
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("_interrupt", interaction), "ctx-1"));
        A2AProtocolAdapter adapter = requestAdapter(false, Map.of());
        CapturingEventQueue queue = new CapturingEventQueue();

        RequestContext context = requestContext("task-1", "ctx-1", false);

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        assertThat(inputRequiredMessage(queue).metadata()).containsOnly(Map.entry("_interrupt", interaction));
    }

    @Test
    void streamingInterruptWithoutMessageStoresRawData() {
        Map<String, Object> interaction = Map.of("type", "__interaction__", "index", 0, "payload",
                Map.of("kind", "confirmation", "items", List.of(Map.of("name", "transfer", "type", "tool_call"))),
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
    void inputRequiredQueueRemainsOpenWhenDrainTimesOut() {
        NeverDrainingEventQueue queue = new NeverDrainingEventQueue();

        A2AAgentExecutor.closeWhenDrained(queue, "task-1", 0L);

        assertThat(queue.closeCalls.get()).isZero();
    }

    @Test
    void arbitraryRuntimeFailureEmitsFailedStatusWithBusinessError() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenThrow(new UnsupportedOperationException("remote boom"));
        RequestContext context = requestContext("task-1", "ctx-1", false);
        A2AProtocolAdapter adapter = requestAdapter(false, Map.of());
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        assertThat(queue.events).filteredOn(TaskStatusUpdateEvent.class::isInstance)
                .map(TaskStatusUpdateEvent.class::cast)
                .filteredOn(event -> event.status().state() == TaskState.TASK_STATE_FAILED).singleElement()
                .satisfies(event -> {
                    assertThat(event.status().message().parts()).singleElement().isInstanceOfSatisfying(
                            TextPart.class, part -> assertThat(part.text()).isEqualTo("Agent execution failed"));
                    assertThat(event.status().message().metadata()).containsEntry(A2aErrorMetadata.KEY,
                            Map.of("schemaVersion", "1", "code", "AGENT_EXECUTION_FAILED", "retryable", false));
                });
    }

    @Test
    void previouslyHandledFailureKeepsBusinessMessage() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenThrow(new IllegalStateException("remote boom"));
        RequestContext context = requestContext("task-1", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of())).execute(context,
                new AgentEmitter(context, queue));

        assertThat(queue.events).filteredOn(TaskStatusUpdateEvent.class::isInstance)
                .map(TaskStatusUpdateEvent.class::cast)
                .filteredOn(event -> event.status().state() == TaskState.TASK_STATE_FAILED).singleElement()
                .satisfies(event -> assertThat(event.status().message().parts()).singleElement()
                        .isInstanceOfSatisfying(TextPart.class,
                                part -> assertThat(part.text()).isEqualTo("remote boom")));
    }

    @Test
    void structuredFailureMetadataIsPreservedOnFailedStatus() {
        AgentFailureDescriptor failure = new AgentFailureDescriptor("MODEL_CALL_FAILED", 181001, false);
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenThrow(new AgentExecutionException("model failed", failure, null));
        RequestContext context = requestContext("task-1", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of())).execute(context,
                new AgentEmitter(context, queue));

        assertThat(queue.events).filteredOn(TaskStatusUpdateEvent.class::isInstance)
                .map(TaskStatusUpdateEvent.class::cast)
                .filteredOn(event -> event.status().state() == TaskState.TASK_STATE_FAILED).singleElement()
                .satisfies(event -> assertThat(event.status().message().metadata())
                        .containsEntry(A2aErrorMetadata.KEY, A2aErrorMetadata.encode(failure)));
    }

    @Test
    void streamingFailureEmitsFailedStatusWithBusinessError() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> observer
                .onError(new IllegalStateException("remote stream boom")))).when(orchestrator)
                .streamQuery(any(), any());
        RequestContext context = requestContext("task-1", "ctx-1", true);
        A2AProtocolAdapter adapter = requestAdapter(true, Map.of());
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        assertThat(queue.events).filteredOn(TaskStatusUpdateEvent.class::isInstance)
                .map(TaskStatusUpdateEvent.class::cast)
                .filteredOn(event -> event.status().state() == TaskState.TASK_STATE_FAILED).singleElement()
                .satisfies(event -> assertThat(event.status().message().parts()).singleElement().isInstanceOfSatisfying(
                        TextPart.class, part -> assertThat(part.text()).isEqualTo("remote stream boom")));
    }

    @Test
    void streamingErrorChunkFailsWithoutArtifactOrCompletedStatus() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        doAnswer(answerVoid((ServeRequest request, QueryStreamObserver observer) -> {
            observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR, Map.of("type", "error", "payload",
                    Map.of("output", "LLM connection refused", "result_type", "error"))));
            observer.onComplete();
        })).when(orchestrator).streamQuery(any(), any());
        RequestContext context = requestContext("task-1", "ctx-1", true);
        A2AProtocolAdapter adapter = requestAdapter(true, Map.of());
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, adapter).execute(context, new AgentEmitter(context, queue));

        assertThat(queue.events).noneMatch(TaskArtifactUpdateEvent.class::isInstance);
        assertThat(queue.events).filteredOn(TaskStatusUpdateEvent.class::isInstance)
                .map(TaskStatusUpdateEvent.class::cast)
                .filteredOn(event -> event.status().state() == TaskState.TASK_STATE_FAILED).singleElement()
                .satisfies(event -> assertThat(event.status().message().parts()).singleElement().isInstanceOfSatisfying(
                        TextPart.class, part -> assertThat(part.text()).isEqualTo("LLM connection refused")));
        assertThat(queue.events).filteredOn(TaskStatusUpdateEvent.class::isInstance)
                .map(TaskStatusUpdateEvent.class::cast)
                .noneMatch(event -> event.status().state() == TaskState.TASK_STATE_COMPLETED);
    }

    @Test
    void copiesOnlyStoredInterruptOntoResumeRequest() {
        Map<String, Object> interaction = Map.of("kind", "message", "message", "Continue");
        Message statusMessage = Message.builder().role(Message.Role.ROLE_AGENT).parts(List.of(new TextPart("Continue")))
                .metadata(Map.of("_interrupt", interaction, "trace", "not-resume-data")).build();
        Task task = Task.builder().id("task-1").contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, statusMessage, null)).build();

        assertStoredInterruptCopied(task, interaction);
    }

    @Test
    void copiesInterruptFromLatestAgentMessageInHistory() {
        Map<String, Object> oldInteraction = Map.of("kind", "message", "message", "Old interrupt");
        Map<String, Object> latestInteraction = Map.of("kind", "confirmation", "message", "Approve");
        Message oldInputRequiredMessage = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.of(new TextPart("Old interrupt"))).metadata(Map.of("_interrupt", oldInteraction)).build();
        Message latestInputRequiredMessage = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.of(new TextPart("Approve")))
                .metadata(Map.of("_interrupt", latestInteraction, "trace", "not-resume-data")).build();
        Message resumeMessage = Message.builder().role(Message.Role.ROLE_USER).parts(List.of(new TextPart("APPROVE")))
                .metadata(Map.of("_interrupt", Map.of("payload", Map.of("kind", "forged")))).build();
        Task task = Task.builder().id("task-1").contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
                .history(List.of(oldInputRequiredMessage, latestInputRequiredMessage, resumeMessage)).build();

        assertStoredInterruptCopied(task, latestInteraction);
    }

    @Test
    void doesNotReuseHistoryInterruptWhenCurrentAgentStatusHasNoInterrupt() {
        Map<String, Object> interaction = Map.of("kind", "confirmation", "message", "Approve");
        Message inputRequiredMessage = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.of(new TextPart("Approve"))).metadata(Map.of("_interrupt", interaction)).build();
        Message unrelatedStatusMessage = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.of(new TextPart("Working"))).build();
        Task task = Task.builder().id("task-1").contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, unrelatedStatusMessage, null))
                .history(List.of(inputRequiredMessage)).build();

        ServeRequest request = executeWithStoredTask(task, mock(AgentEmitter.class), Map.of());

        assertThat(request.getMetadata()).doesNotContainKey("_interrupt");
    }

    @Test
    void doesNotReuseOldInterruptWhenLatestAgentMessageHasNoMarker() {
        Message oldInputRequiredMessage = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.of(new TextPart("Old interrupt")))
                .metadata(Map.of("_interrupt", Map.of("kind", "confirmation"))).build();
        Message latestAgentMessage = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.of(new TextPart("Current input required"))).build();
        Message resumeMessage = Message.builder().role(Message.Role.ROLE_USER).parts(List.of(new TextPart("continue")))
                .build();
        Task task = Task.builder().id("task-1").contextId("ctx-1")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED))
                .history(List.of(oldInputRequiredMessage, latestAgentMessage, resumeMessage)).build();

        ServeRequest request = executeWithStoredTask(task, mock(AgentEmitter.class), Map.of());

        assertThat(request.getMetadata()).doesNotContainKey("_interrupt");
    }

    @Test
    void removesClientInterruptFromNonInputRequiredRequest() {
        Map<String, Object> interaction = Map.of("kind", "message", "message", "Old interrupt");
        Message oldInterrupt = Message.builder().role(Message.Role.ROLE_AGENT)
                .parts(List.of(new TextPart("Old interrupt"))).metadata(Map.of("_interrupt", interaction)).build();
        Task task = Task.builder().id("task-1").contextId("ctx-1").status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                .history(List.of(oldInterrupt)).build();

        AgentEmitter emitter = mock(AgentEmitter.class);
        ServeRequest request = executeWithStoredTask(task, emitter,
                Map.of("_interrupt", Map.of("payload", Map.of("kind", "forged"))));

        assertThat(request.getMetadata()).doesNotContainKey("_interrupt");
        verify(emitter, never()).submit();
    }

    @Test
    void execute_rejectedWithA2AError_whenAdmissionGateFull() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(false);
        RequestContext context = requestContext("task-1", "ctx-1", false);
        AgentEmitter emitter = mock(AgentEmitter.class);

        A2AAgentExecutor executor = new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()), gate);

        assertThatThrownBy(() -> executor.execute(context, emitter))
                .isInstanceOf(A2AError.class)
                .hasMessageContaining("concurrent task limit reached");
        verify(orchestrator, never()).query(any());
        verify(orchestrator, never()).streamQuery(any(), any());
        verify(gate, never()).release();
        verify(emitter, never()).submit();
        verify(emitter, never()).startWork();
    }

    @Test
    void execute_admissionAcquired_releasedExactlyOnce_onSuccess() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        RequestContext context = requestContext("task-1", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()), gate)
                .execute(context, new AgentEmitter(context, queue));

        verify(orchestrator).query(any());
        verify(gate, times(1)).release();
    }

    @Test
    void execute_admissionAcquired_releasedExactlyOnce_onAgentFailure() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenThrow(new IllegalStateException("agent failed"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        RequestContext context = requestContext("task-1", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()), gate)
                .execute(context, new AgentEmitter(context, queue));

        verify(gate, times(1)).release();
    }

    @Test
    void execute_admissionAcquired_releasedExactlyOnce_onAgentError() {
        // S-22 quota semantics: an Error (e.g. OOM) escapes the executor
        // unchanged, but the finally block must still release the permit so
        // the failure does not permanently consume quota.
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenThrow(new AssertionError("simulated OOM"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        RequestContext context = requestContext("task-1", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        assertThatThrownBy(() -> new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()), gate)
                .execute(context, new AgentEmitter(context, queue)))
                .isInstanceOf(AssertionError.class)
                .hasMessage("simulated OOM");
        verify(gate, times(1)).release();
    }

    @Test
    void execute_nullGate_executesWithoutAdmission() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        RequestContext context = requestContext("task-1", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()))
                .execute(context, new AgentEmitter(context, queue));

        verify(orchestrator).query(any());
    }

    @Test
    void execute_admissionListenerNotified_beforeExecutionAndAfterRelease() {
        // Probe-alignment contract: onAdmitted fires right after tryAcquire
        // (before the handler runs) and onReleased fires in the same finally
        // as release() — so a listener-backed snapshot never diverges from
        // gate.currentCount().
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        RequestContext context = requestContext("task-probe", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()), gate, listener)
                .execute(context, new AgentEmitter(context, queue));

        InOrder inOrder = inOrder(listener, gate, orchestrator);
        inOrder.verify(gate).tryAcquire();
        inOrder.verify(listener).onAdmitted("task-probe", "ctx-1");
        inOrder.verify(orchestrator).query(any());
        inOrder.verify(gate).release();
        inOrder.verify(listener).onReleased("task-probe", "ctx-1");
    }

    @Test
    void execute_admissionListenerReleased_onAgentFailure() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenThrow(new IllegalStateException("agent failed"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        RequestContext context = requestContext("task-fail", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()), gate, listener)
                .execute(context, new AgentEmitter(context, queue));

        verify(listener).onAdmitted("task-fail", "ctx-1");
        verify(listener).onReleased("task-fail", "ctx-1");
    }

    @Test
    void execute_admissionListenerThrows_releasesPermitAndCompensatesOnReleased() {
        // Issue #96: onAdmitted runs inside the admission try-finally scope, so
        // a throwing listener must not leak the quota slot — the permit is
        // released exactly once and onReleased fires as compensation.
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(true);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        doThrow(new IllegalStateException("listener backend down")).when(listener).onAdmitted(any(), any());
        RequestContext context = requestContext("task-leak", "ctx-1", false);
        CapturingEventQueue queue = new CapturingEventQueue();

        // The listener failure propagates as the request failure (SDK error
        // path), but the permit is returned first.
        catchThrowable(() -> new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()), gate, listener)
                .execute(context, new AgentEmitter(context, queue)));

        InOrder inOrder = inOrder(listener, gate, orchestrator);
        inOrder.verify(gate).tryAcquire();
        inOrder.verify(listener).onAdmitted("task-leak", "ctx-1");
        inOrder.verify(gate).release();
        inOrder.verify(listener).onReleased("task-leak", "ctx-1");
        // The agent never ran: execution is aborted before orchestration.
        verify(orchestrator, never()).query(any());
    }

    @Test
    void execute_admissionListenerSkipped_whenAdmissionRejected() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        TaskAdmissionGate gate = mock(TaskAdmissionGate.class);
        when(gate.tryAcquire()).thenReturn(false);
        TaskAdmissionListener listener = mock(TaskAdmissionListener.class);
        RequestContext context = requestContext("task-reject", "ctx-1", false);
        AgentEmitter emitter = mock(AgentEmitter.class);

        assertThatThrownBy(() -> new A2AAgentExecutor(orchestrator, requestAdapter(false, Map.of()), gate, listener)
                .execute(context, emitter)).isInstanceOf(A2AError.class);

        verify(listener, never()).onAdmitted(any(), any());
        verify(listener, never()).onReleased(any(), any());
    }

    private static void assertStoredInterruptCopied(Task task, Map<String, Object> interaction) {
        ServeRequest request = executeWithStoredTask(task, mock(AgentEmitter.class), Map.of());

        assertThat(request.getMetadata()).containsEntry("_interrupt", interaction).doesNotContainKey("trace");
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
        return queue.events.stream().filter(TaskStatusUpdateEvent.class::isInstance)
                .map(TaskStatusUpdateEvent.class::cast).map(TaskStatusUpdateEvent::status)
                .map(status -> status.message()).filter(java.util.Objects::nonNull).findFirst().orElseThrow();
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

    private static final class NeverDrainingEventQueue extends CountingEventQueue {
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int size() {
            return 1;
        }

        @Override
        public void close(boolean isImmediate, boolean shouldNotifyParent) {
            closeCalls.incrementAndGet();
        }
    }
}
