/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteCallOutcome;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.StreamCancellationHandle;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.InMemoryTaskStore;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for the A2A-enabled serve orchestrator.
 */
class A2AEnabledServeOrchestratorTest {
    private AgentHandler agentHandler;

    private TaskStore taskStore;

    private ActiveStreamRegistry streamRegistry;

    private RemoteAgentCaller a2aClient;

    private A2AEnabledServeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        agentHandler = mock(AgentHandler.class);
        taskStore = mock(TaskStore.class);
        a2aClient = mock(A2ARemoteAgentClient.class);
        streamRegistry = mock(ActiveStreamRegistry.class);
        when(streamRegistry.register(anyString())).thenReturn(mock(StreamCancellationHandle.class));
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
    }

    @Test
    void normalChunksPassThroughToObserver() {
        when(taskStore.get(anyString())).thenReturn(null);
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk("chunk", "hello"));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        AtomicBoolean completed = new AtomicBoolean(false);
        orchestrator.streamQuery(req("c1"), new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk c) {
                assertThat(c.getData()).isEqualTo("hello");
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }

            @Override
            public void onError(Throwable e) {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        });

        assertThat(completed.get()).isTrue();
    }

    @Test
    void streamingClientToolResumeBypassesRemoteBatchProbe() {
        ServeRequest request = req("c-client-tool-stream");
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(Map.of(
            "runtime.parentTaskId", "task-client-tool-stream",
            "runtime.remoteToolInputs", Map.of("call-a", "page body"),
            "_interrupt", clientToolInterrupt("call-a", "readCurrentPage")));
        request.setMetadata(metadata);
        doAnswer(invocation -> {
            ServeRequest actual = invocation.getArgument(0);
            assertThat(actual).isSameAs(request);
            QueryStreamObserver observer = invocation.getArgument(1);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "final"));
            observer.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        Map<String, Object> expectedMetadata = new java.util.LinkedHashMap<>(metadata);

        orchestrator.streamQuery(request, observer);

        verify(agentHandler).streamQuery(any(), any());
        verify(observer).onNext(argThat(chunk -> "final".equals(chunk.getData())));
        verify(observer).onComplete();
        verify(observer, never()).onError(any());
        assertThat(request.getMetadata()).isEqualTo(expectedMetadata);
    }

    @Test
    void synchronousClientToolBatchResumeBypassesRemoteBatchProbe() {
        ServeRequest request = req("c-client-tool-query");
        Map<String, Object> metadata = new java.util.LinkedHashMap<>(Map.of(
            "runtime.parentTaskId", "task-client-tool-query",
            "runtime.remoteToolInputs", Map.of("call-a", "page body", "call-b", "confirmed"),
            "_interrupt", Map.of("items", List.of(
                clientToolInterrupt("call-a", "readCurrentPage"),
                clientToolInterrupt("call-b", "confirmLocalAction")))));
        Map<String, Object> expectedMetadata = new java.util.LinkedHashMap<>(metadata);
        request.setMetadata(metadata);
        when(agentHandler.query(request)).thenReturn(
            new QueryResponse(Map.of("content", "final"), request.getConversationId()));

        QueryResponse response = orchestrator.query(request);

        assertThat(response.getResult()).isEqualTo(Map.of("content", "final"));
        verify(agentHandler).query(request);
        assertThat(request.getMetadata()).isEqualTo(expectedMetadata);
    }

    @Test
    void onlyPureClientToolInterruptsBypassRemoteBatchProbe() {
        List<Map<String, Object>> interrupts = List.of(
            Map.of("items", List.of(
                clientToolInterrupt("call-a", "readCurrentPage"),
                Map.of("context", Map.of("_interrupt_kind", "ask_user")))),
            Map.of("items", List.of(Map.of("context", Map.of("_interrupt_kind", "ask_user")))),
            Map.of("items", List.of()));

        for (int index = 0; index < interrupts.size(); index++) {
            ServeRequest request = req("c-not-client-tool-" + index);
            request.setMetadata(new java.util.LinkedHashMap<>(Map.of(
                "runtime.parentTaskId", "task-not-client-tool-" + index,
                "runtime.remoteToolInputs", Map.of("call-a", "input"),
                "_interrupt", interrupts.get(index))));
            QueryStreamObserver observer = mock(QueryStreamObserver.class);

            orchestrator.streamQuery(request, observer);

            verify(observer).onError(argThat(error -> error instanceof IllegalArgumentException
                && error.getMessage().contains("REMOTE_BATCH_PARENT_MISMATCH")));
            verify(observer, never()).onComplete();
        }
        verify(agentHandler, never()).streamQuery(any(), any());
    }

    @Test
    void multipleLocalInterruptsAreForwardedWithoutRemoteDispatch() {
        Map<String, Object> interrupt = Map.of("items", List.of(
            Map.of("toolCallId", "call-a", "message", "question-a",
                "context", Map.of("_interrupt_kind", "ask_user")),
            Map.of("toolCallId", "call-b", "message", "question-b",
                "context", Map.of("_interrupt_kind", "ask_user"))));
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, interrupt));
            observer.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);

        orchestrator.streamQuery(req("c-local-interrupts"), observer);

        verify(observer).onNext(argThat(chunk -> QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())
            && interrupt.equals(chunk.getData())));
        verify(observer).onComplete();
        verify(a2aClient, never()).callOutcome(any(), any());
    }

    @Test
    void mixedRemoteAndLocalInterruptBatchIsRejectedInStreamingMode() {
        Map<String, Object> interrupt = mixedInterruptBatch();
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, interrupt));
            observer.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);

        orchestrator.streamQuery(req("c-mixed-stream"), observer);

        verify(observer).onError(argThat(error -> error instanceof IllegalArgumentException
            && error.getMessage().contains("CORE_INTERRUPT_KIND_MIXED_UNSUPPORTED")));
        verify(a2aClient, never()).callOutcome(any(), any());
    }

    @Test
    void mixedRemoteAndLocalInterruptBatchIsRejectedInQueryMode() {
        Map<String, Object> result = Map.of(
            "role", "assistant",
            "content", "interaction batch",
            "_interrupt", mixedInterruptBatch());
        when(agentHandler.query(any())).thenReturn(new QueryResponse(result, "c-mixed-query"));

        assertThatThrownBy(() -> orchestrator.query(req("c-mixed-query")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("CORE_INTERRUPT_KIND_MIXED_UNSUPPORTED");
        verify(a2aClient, never()).callOutcome(any(), any());
    }

    @Test
    void queryKeepsSynchronousHandlerAndDisablesRemoteStreaming() {
        taskStore = new InMemoryTaskStore();
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        when(a2aClient.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            assertThat(call.isCallerStreaming()).isFalse();
            return CompletableFuture.completedFuture(new RemoteCallOutcome(
                "remote-task", TaskState.TASK_STATE_COMPLETED, "COMPLETED", "result-" + call.message(), null));
        });
        AtomicInteger localRuns = new AtomicInteger();
        when(agentHandler.query(any())).thenAnswer(invocation -> {
            if (localRuns.getAndIncrement() == 0) {
                return new QueryResponse(Map.of("_interrupt", remoteBatch()), "c-query-progress");
            }
            return new QueryResponse(Map.of("content", "final"), "c-query-progress");
        });
        ServeRequest request = req("c-query-progress");
        request.setStream(false);
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-query-progress"));

        QueryResponse response = orchestrator.query(request);

        assertThat(response.getResult()).isEqualTo(Map.of("content", "final"));
        verify(agentHandler, times(2)).query(any());
        verify(agentHandler, never()).streamQuery(any(), any());
    }

    @Test
    void localInterruptWithToolCallIdBypassesRemoteDispatch() {
        Map<String, Object> interrupt = Map.of(
            "type", "__interaction__",
            "toolCallId", "call-local",
            "message", "question",
            "context", Map.of("_interrupt_kind", "ask_user"));
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, interrupt));
            observer.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);

        orchestrator.streamQuery(req("c-local-interrupt"), observer);

        verify(observer).onNext(argThat(chunk -> QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())
            && interrupt.equals(chunk.getData())));
        verify(observer).onComplete();
        verify(a2aClient, never()).callOutcome(any(), any());
    }

    @Test
    void remoteInterruptWithoutToolCallIdIsRejected() {
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of(
                "type", "__interaction__",
                "message", "legacy remote request",
                "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "legacy-agent"))));
            observer.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);

        orchestrator.streamQuery(req("c-missing-tool-call-id"), observer);

        verify(observer).onError(argThat(error -> error instanceof IllegalArgumentException
            && error.getMessage().contains("CORE_INTERRUPT_CORRELATION_MISSING")));
        verify(a2aClient, never()).callOutcome(any(), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void remoteBatchFansOutAndResumesCoreWithCompleteResults() {
        taskStore = new InMemoryTaskStore();
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        when(a2aClient.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            assertThat(call.isCallerStreaming()).isTrue();
            String id = call.message().substring("message-".length());
            return CompletableFuture.completedFuture(new RemoteCallOutcome(
                "remote-" + id,
                TaskState.TASK_STATE_COMPLETED,
                "COMPLETED",
                "result-" + id,
                null));
        });
        AtomicInteger localRuns = new AtomicInteger();
        doAnswer(invocation -> {
            ServeRequest request = invocation.getArgument(0);
            QueryStreamObserver observer = invocation.getArgument(1);
            if (localRuns.getAndIncrement() == 0) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, remoteBatch()));
            } else {
                Task readyShadow = taskStore.get("shadow:test-agent:parent-batch");
                assertThat(readyShadow).isNotNull();
                assertThat((Map<String, Object>) readyShadow.metadata().get("_remote_batch"))
                    .containsEntry("state", "READY_TO_RESUME");
                Map<String, Object> results = (Map<String, Object>) request.getMetadata()
                    .get("runtime.remoteToolResults");
                assertThat(results).containsOnly(
                    Map.entry("call-a", "result-call-a"),
                    Map.entry("call-b", "result-call-b"),
                    Map.entry("call-c", "result-call-c"));
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "final"));
            }
            observer.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        ServeRequest request = req("c-batch");
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-batch"));

        orchestrator.streamQuery(request, observer);

        verify(a2aClient, times(3)).callOutcome(any(), any());
        assertThat(localRuns.get()).isEqualTo(2);
        assertThat(taskStore.get("shadow:test-agent:parent-batch")).isNull();
        verify(observer).onComplete();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resumeFalseStreamingReturnsRemoteAnswerWithoutReInvokingAgent() {
        taskStore = new InMemoryTaskStore();
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        when(a2aClient.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new RemoteCallOutcome(
                "remote-" + call.agentName(),
                TaskState.TASK_STATE_COMPLETED,
                "COMPLETED",
                "intent-answer",
                null));
        });
        AtomicInteger localRuns = new AtomicInteger();
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            if (localRuns.getAndIncrement() == 0) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of(
                    "type", "__interaction__",
                    "toolCallId", "call-intent",
                    "toolName", "intent-tool",
                    "message", "go",
                    "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "intent-agent",
                        "resume", false))));
            }
            observer.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        ServeRequest request = req("c-intent");
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-intent"));

        orchestrator.streamQuery(request, observer);

        assertThat(localRuns.get()).isEqualTo(1);
        verify(a2aClient, times(1)).callOutcome(any(), any());
        verify(observer).onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "intent-answer"));
        verify(observer).onComplete();
        verify(observer, never()).onNext(argThat(chunk -> chunk != null
            && QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())));
        assertThat(taskStore.get("shadow:test-agent:parent-intent")).isNull();
    }

    @Test
    void resumeFalseReturnsRemoteAnswerWithoutReInvokingAgent() {
        taskStore = new InMemoryTaskStore();
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        when(a2aClient.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new RemoteCallOutcome(
                "remote-" + call.agentName(),
                TaskState.TASK_STATE_COMPLETED,
                "COMPLETED",
                "intent-answer",
                null));
        });
        AtomicInteger localRuns = new AtomicInteger();
        when(agentHandler.query(any())).thenAnswer(invocation -> {
            if (localRuns.getAndIncrement() == 0) {
                return new QueryResponse(Map.of(
                    "_interrupt", Map.of(
                        "type", "__interaction__",
                        "toolCallId", "call-intent",
                        "toolName", "intent-tool",
                        "message", "go",
                        "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "intent-agent",
                            "resume", false))),
                    "c-intent-query");
            }
            throw new IllegalStateException("agent must not be re-invoked when resume=false");
        });
        ServeRequest request = req("c-intent-query");
        request.setStream(false);
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-intent-query"));

        QueryResponse response = orchestrator.query(request);

        assertThat(localRuns.get()).isEqualTo(1);
        assertThat(response.getResult()).isInstanceOf(Map.class);
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertThat(result.get("role")).isEqualTo("assistant");
        assertThat(result.get("content")).isEqualTo("intent-answer");
        assertThat(result.get("_interrupt")).isNull();
        assertThat(taskStore.get("shadow:test-agent:parent-intent-query")).isNull();
    }

    @Test
    void mixedResumeFlagsInBatchAreRejected() {
        taskStore = new InMemoryTaskStore();
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        List<Map<String, Object>> items = List.of(
            Map.of(
                "toolCallId", "call-keep",
                "toolName", "tool-keep",
                "message", "go",
                "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "agent-a", "resume", true)),
            Map.of(
                "toolCallId", "call-intent",
                "toolName", "tool-intent",
                "message", "go",
                "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "agent-b", "resume", false)));
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("items", items)));
            observer.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        ServeRequest request = req("c-mixed");
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-mixed"));

        orchestrator.streamQuery(request, observer);

        verify(observer).onError(argThat(error ->
            error instanceof IllegalArgumentException
                && "CORE_INTERRUPT_RESUME_MIXED_UNSUPPORTED".equals(error.getMessage())));
        verify(a2aClient, never()).callOutcome(any(), any());
    }

    @Test
    void coreResumeFailureKeepsReadyShadowForRetry() {
        taskStore = new InMemoryTaskStore();
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        when(a2aClient.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            String id = call.message().substring("message-".length());
            return CompletableFuture.completedFuture(new RemoteCallOutcome(
                "remote-" + id, TaskState.TASK_STATE_COMPLETED, "COMPLETED", "result-" + id, null));
        });
        AtomicInteger localRuns = new AtomicInteger();
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            if (localRuns.getAndIncrement() == 0) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, remoteBatch()));
                observer.onComplete();
            } else {
                observer.onError(new IllegalStateException("core resume failed"));
            }
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        ServeRequest request = req("c-batch-failure");
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-batch-failure"));

        orchestrator.streamQuery(request, mock(QueryStreamObserver.class));

        Task readyShadow = taskStore.get("shadow:test-agent:parent-batch-failure");
        assertThat(readyShadow).isNotNull();
        @SuppressWarnings("unchecked") Map<String, Object> snapshot =
            (Map<String, Object>) readyShadow.metadata().get("_remote_batch");
        assertThat(snapshot).containsEntry("state", "READY_TO_RESUME");
    }

    @Test
    void shadowCleanupFailureStopsBeforeHandlingCapturedNextInterrupt() {
        taskStore = spy(new InMemoryTaskStore());
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        when(a2aClient.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new RemoteCallOutcome(
                "remote-task", TaskState.TASK_STATE_COMPLETED, "COMPLETED", "result-" + call.message(), null));
        });
        AtomicInteger localRuns = new AtomicInteger();
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            if (localRuns.getAndIncrement() == 0) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, remoteBatch()));
                observer.onComplete();
            } else {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, remoteBatch()));
                try {
                    observer.onComplete();
                } catch (IllegalStateException ex) {
                    observer.onNext(new QueryChunk(QueryChunk.TYPE_ERROR, Map.of("error", ex.getMessage())));
                    observer.onError(ex);
                }
            }
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        doThrow(new IllegalStateException("shadow delete failed"))
            .when(taskStore).delete("shadow:test-agent:parent-cleanup-failure");
        ServeRequest request = req("c-cleanup-failure");
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-cleanup-failure"));

        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        orchestrator.streamQuery(request, observer);

        assertThat(localRuns.get()).isEqualTo(2);
        assertThat(taskStore.get("shadow:test-agent:parent-cleanup-failure")).isNotNull();
        verify(observer, times(1)).onError(any());
    }

    @Test
    void duplicateStreamingResumeReportsInFlightConflict() throws Exception {
        taskStore = new InMemoryTaskStore();
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        when(a2aClient.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new RemoteCallOutcome(
                "remote-task", TaskState.TASK_STATE_COMPLETED, "COMPLETED", "result-" + call.message(), null));
        });
        CountDownLatch resumeEntered = new CountDownLatch(1);
        CountDownLatch allowResume = new CountDownLatch(1);
        AtomicInteger localRuns = new AtomicInteger();
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            if (localRuns.getAndIncrement() == 0) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, remoteBatch()));
                observer.onComplete();
            } else {
                resumeEntered.countDown();
                assertThat(allowResume.await(5, TimeUnit.SECONDS)).isTrue();
                observer.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "final"));
                observer.onComplete();
            }
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        ServeRequest request = req("c-duplicate-resume");
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-duplicate-resume"));
        QueryStreamObserver secondObserver = mock(QueryStreamObserver.class);

        CompletableFuture<Void> first = CompletableFuture.runAsync(
            () -> orchestrator.streamQuery(request, mock(QueryStreamObserver.class)));
        assertThat(resumeEntered.await(5, TimeUnit.SECONDS)).isTrue();
        orchestrator.streamQuery(request, secondObserver);
        allowResume.countDown();
        first.get(5, TimeUnit.SECONDS);

        assertThat(localRuns.get()).isEqualTo(2);
        verify(secondObserver).onError(argThat(error -> error instanceof IllegalStateException
            && error.getMessage().contains("REMOTE_BATCH_CORE_RESUME_IN_FLIGHT")));
    }

    @Test
    void duplicateQueryResumeReportsInFlightConflict() throws Exception {
        taskStore = new InMemoryTaskStore();
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        when(a2aClient.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new RemoteCallOutcome(
                "remote-task", TaskState.TASK_STATE_COMPLETED, "COMPLETED", "result-" + call.message(), null));
        });
        CountDownLatch resumeEntered = new CountDownLatch(1);
        CountDownLatch allowResume = new CountDownLatch(1);
        AtomicInteger localRuns = new AtomicInteger();
        when(agentHandler.query(any())).thenAnswer(invocation -> {
            int run = localRuns.getAndIncrement();
            if (run == 0) {
                return new QueryResponse(Map.of("_interrupt", remoteBatch()), "c-duplicate-query-resume");
            }
            resumeEntered.countDown();
            assertThat(allowResume.await(5, TimeUnit.SECONDS)).isTrue();
            return new QueryResponse(Map.of("content", "final"), "c-duplicate-query-resume");
        });
        ServeRequest request = req("c-duplicate-query-resume");
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-duplicate-query-resume"));

        CompletableFuture<QueryResponse> first = CompletableFuture.supplyAsync(() -> orchestrator.query(request));
        assertThat(resumeEntered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> orchestrator.query(request))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("REMOTE_BATCH_CORE_RESUME_IN_FLIGHT");
        allowResume.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).getResult()).isEqualTo(Map.of("content", "final"));
        assertThat(localRuns.get()).isEqualTo(2);
    }

    @Test
    void synchronousCoreResumeFailureReleasesClaimForRetry() {
        taskStore = new InMemoryTaskStore();
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        when(a2aClient.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            return CompletableFuture.completedFuture(new RemoteCallOutcome(
                "remote-task", TaskState.TASK_STATE_COMPLETED, "COMPLETED", "result-" + call.message(), null));
        });
        AtomicInteger localRuns = new AtomicInteger();
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            int run = localRuns.getAndIncrement();
            if (run == 0) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, remoteBatch()));
                observer.onComplete();
            } else if (run == 1) {
                throw new IllegalStateException("synchronous core failure");
            } else {
                observer.onComplete();
            }
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        ServeRequest request = req("c-sync-core-failure");
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-sync-core-failure"));

        assertThatThrownBy(() -> orchestrator.streamQuery(request, mock(QueryStreamObserver.class)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("synchronous core failure");
        orchestrator.streamQuery(request, mock(QueryStreamObserver.class));

        assertThat(localRuns.get()).isEqualTo(3);
        assertThat(taskStore.get("shadow:test-agent:parent-sync-core-failure")).isNull();
    }

    @Test
    void cancelledRequestAfterReadyResultReleasesClaimForRetry() {
        taskStore = new InMemoryTaskStore();
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, streamRegistry,
            "test-agent", 16, 256, 30);
        AtomicInteger remoteCalls = new AtomicInteger();
        when(a2aClient.callOutcome(any(), any())).thenAnswer(invocation -> {
            RemoteCall call = invocation.getArgument(0);
            remoteCalls.incrementAndGet();
            return CompletableFuture.completedFuture(new RemoteCallOutcome(
                "remote-task", TaskState.TASK_STATE_COMPLETED, "COMPLETED", "result-" + call.message(), null));
        });
        AtomicInteger localRuns = new AtomicInteger();
        doAnswer(invocation -> {
            QueryStreamObserver observer = invocation.getArgument(1);
            if (localRuns.getAndIncrement() == 0) {
                observer.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, remoteBatch()));
            }
            observer.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver cancellingObserver = new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public boolean isCancelled() {
                return remoteCalls.get() == 3;
            }
        };
        ServeRequest request = req("c-cancelled-ready");
        request.setMetadata(Map.of("runtime.parentTaskId", "parent-cancelled-ready"));

        orchestrator.streamQuery(request, cancellingObserver);
        orchestrator.streamQuery(request, mock(QueryStreamObserver.class));

        assertThat(localRuns.get()).isEqualTo(2);
        assertThat(taskStore.get("shadow:test-agent:parent-cancelled-ready")).isNull();
    }

    @Test
    void interruptWithoutA2aInterruptChunkDoesNotCreateShadowTask() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of()));
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk("chunk", "normal data"));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        orchestrator.streamQuery(req("c-normal"), mock(QueryStreamObserver.class));

        verify(taskStore, never()).save(any(), anyBoolean());
    }
    @Test
    void noPendingTaskDelegatesToAgent() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of()));

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        orchestrator.streamQuery(req("c-clean"), mock(QueryStreamObserver.class));

        verify(agentHandler).streamQuery(any(), any());
    }

    @Test
    void resetConversationCleansTaskStore() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of(
            Task.builder().id("t1").contextId("c-reset").status(new TaskStatus(TaskState.TASK_STATE_COMPLETED)).build(),
            Task.builder()
                .id("t2")
                .contextId("c-reset")
                .status(new TaskStatus(TaskState.TASK_STATE_WORKING))
                .build())));

        orchestrator.resetConversation("c-reset");

        verify(agentHandler).clearSession("c-reset");
        verify(taskStore).delete("t1");
        verify(taskStore).delete("t2");
    }

    @Test
    void resetWithNoTasksCleansOnlySession() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of()));

        orchestrator.resetConversation("c-empty");

        verify(agentHandler).clearSession("c-empty");
        verify(taskStore, never()).delete(any());
    }

    @Test
    void streamQuery_prepareTaskThrows_unregistersHandleAndCallsCompleteTask() {
        ActiveStreamRegistry realRegistry = new ActiveStreamRegistry();
        A2AEnabledServeOrchestrator orchestratorWithRealRegistry = new A2AEnabledServeOrchestrator(agentHandler,
            taskStore, a2aClient, realRegistry, "test-agent", 16, 256, 30);
        doThrow(new IllegalStateException("conversation busy")).when(agentHandler).prepareTask(any());

        assertThatThrownBy(() -> orchestratorWithRealRegistry.streamQuery(req("c-busy-stream"),
            new QueryStreamObserver() {
                @Override
                public void onNext(QueryChunk c) {
                }

                @Override
                public void onComplete() {
                }

                @Override
                public void onError(Throwable e) {
                }

                @Override
                public boolean isCancelled() {
                    return false;
                }
            })).isInstanceOf(IllegalStateException.class);

        // The registered handle must be released even though prepareTask threw
        assertThat(realRegistry.activeCount()).isZero();
        // A task whose prepareTask failed never acquired resources: the finally
        // must pass a null token so the handler cannot disturb another task.
        verify(agentHandler).completeTask(null);
    }

    @Test
    void query_prepareTaskThrows_callsCompleteTask() {
        doThrow(new IllegalStateException("conversation busy")).when(agentHandler).prepareTask(any());

        assertThatThrownBy(() -> orchestrator.query(req("c-busy-query")))
                .isInstanceOf(IllegalStateException.class);

        verify(agentHandler).completeTask(null);
    }

    @Test
    void query_prepareTaskSucceeds_passesTokenToCompleteTask() {
        when(agentHandler.prepareTask(any())).thenReturn(TASK_TOKEN);
        when(agentHandler.query(any())).thenReturn(new QueryResponse(Map.of("role", "assistant",
            "content", "done"), "c-token-stream"));

        orchestrator.query(req("c-token-stream"));

        // The token returned by prepareTask must round-trip to completeTask
        verify(agentHandler).completeTask(TASK_TOKEN);
    }

    private static final Object TASK_TOKEN = new Object();

    private static ServeRequest req(String convId) {
        ServeRequest r = new ServeRequest();
        r.setConversationId(convId);
        r.setStream(true);
        return r;
    }

    private static Map<String, Object> remoteBatch() {
        List<Map<String, Object>> items = List.of("call-a", "call-b", "call-c").stream()
            .map(id -> Map.<String, Object>of(
                "toolCallId", id,
                "toolName", "tool-" + id,
                "message", "message-" + id,
                "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "agent-" + id)))
            .toList();
        return Map.of("batchId", "batch-1", "items", items);
    }

    private static Map<String, Object> mixedInterruptBatch() {
        return Map.of("message", "interaction batch", "items", List.of(
            Map.of(
                "type", "__interaction__",
                "toolCallId", "call-remote",
                "message", "remote request",
                "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "remote-agent")),
            Map.of(
                "type", "__interaction__",
                "toolCallId", "call-local",
                "message", "local question",
                "context", Map.of("_interrupt_kind", "ask_user"))));
    }

    private static Map<String, Object> clientToolInterrupt(String toolCallId, String toolName) {
        return Map.of(
            "type", "__interaction__",
            "toolCallId", toolCallId,
            "toolName", toolName,
            "context", Map.of("_interrupt_kind", "client_tool"));
    }
}
