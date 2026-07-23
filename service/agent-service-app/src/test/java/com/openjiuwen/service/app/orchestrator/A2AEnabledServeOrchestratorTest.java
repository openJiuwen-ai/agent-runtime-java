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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentCardRegistry;
import com.openjiuwen.service.app.controller.a2a.client.A2ARemoteAgentClient;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.app.lifecycle.StreamCancellationHandle;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit tests for the A2A-enabled serve orchestrator.
 */
class A2AEnabledServeOrchestratorTest {
    private AgentHandler agentHandler;

    private TaskStore taskStore;

    private A2ARemoteAgentCardRegistry registry;

    private ActiveStreamRegistry streamRegistry;

    private A2ARemoteAgentClient a2aClient;

    private A2AEnabledServeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        agentHandler = mock(AgentHandler.class);
        taskStore = mock(TaskStore.class);
        a2aClient = mock(A2ARemoteAgentClient.class);
        registry = mock(A2ARemoteAgentCardRegistry.class);
        streamRegistry = mock(ActiveStreamRegistry.class);
        when(streamRegistry.register(anyString())).thenReturn(mock(StreamCancellationHandle.class));
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, a2aClient, registry, streamRegistry,
                "test-agent");
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
    void a2aInterruptCreatesShadowTaskInInputRequired() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of()));

        // Setup: agent produces a2a_interrupt chunk
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                    Map.of("agentName", "hotel-agent", "toolName", "search")));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        var card = testCard();
        when(registry.get("hotel-agent")).thenReturn(
                java.util.Optional.of(new A2ARemoteAgentCardRegistry.RemoteAgentEntry("hotel-agent", card, 300)));
        when(registry.resolveUrl(anyString())).thenReturn("http://remote/a2a/");
        when(a2aClient.callSync(any())).thenThrow(
                new A2ARemoteAgentClient.RemoteInputRequiredException("remote input required", "remote-task-1"));

        orchestrator.streamQuery(req("c-int"), mock(QueryStreamObserver.class));

        // Verify shadow task was saved with INPUT_REQUIRED
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore, atLeastOnce()).save(taskCaptor.capture(), anyBoolean());
        Task saved = taskCaptor.getValue();
        assertThat(saved.contextId()).isEqualTo("c-int");
        assertThat(saved.status().state()).isEqualTo(TaskState.TASK_STATE_INPUT_REQUIRED);
        assertThat(saved.metadata()).containsEntry("_remote_task_id", "remote-task-1");
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
    void pendingTaskRoutesToResume() {
        // Shadow tasks are namespaced by agent identity (see
        // A2AEnabledServeOrchestrator#shadowTaskId).
        String shadowId = "shadow:test-agent:c-pending";
        Task pending = Task.builder().id(shadowId).contextId("c-pending")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id",
                        "remote-task-123"))
                .build();
        when(registry.get("test")).thenReturn(
                java.util.Optional.of(new A2ARemoteAgentCardRegistry.RemoteAgentEntry("test", testCard(), 300)));
        when(taskStore.get(shadowId)).thenReturn(pending);

        orchestrator.streamQuery(req("c-pending"), mock(QueryStreamObserver.class));

        // Verify pending check was made via get() on the namespaced shadow key; remote
        // call
        // attempts but may fail in unit test
        verify(taskStore).get(shadowId);
    }

    @Test
    void pendingResumeWithoutSseModeUsesSyncCallNoPassthrough() {
        String shadowId = "shadow:test-agent:c-sync";
        Task pending = Task.builder().id(shadowId).contextId("c-sync")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1"))
                .build();
        // Deleted after a successful resume, so the second findPending sees nothing.
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        when(a2aClient.callSync(any())).thenReturn("42");

        orchestrator.streamQuery(req("c-sync"), mock(QueryStreamObserver.class));

        // No _stream_mode → resolve synchronously; the client observer is never handed
        // to the remote call.
        verify(a2aClient)
                .callSync(argThat(call -> "test".equals(call.agentName()) && "c-sync".equals(call.contextId())));
        verify(a2aClient, never()).callStreaming(any(), any());
    }

    @Test
    void pendingResumeWithSseModeStreamsThroughObserver() {
        String shadowId = "shadow:test-agent:c-sse";
        Task pending = Task.builder().id(shadowId).contextId("c-sse")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                        "_stream_mode", "sse"))
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.completedFuture("42"));

        orchestrator.streamQuery(req("c-sse"), mock(QueryStreamObserver.class));

        // _stream_mode=sse → stream the remote content to the client observer.
        verify(a2aClient).callStreaming(argThat(c -> "test".equals(c.agentName()) && "c-sse".equals(c.contextId())),
                any());
        verify(a2aClient, never()).callSync(any());
    }

    @Test
    void resumeInputRequiredCompletesObserver() throws Exception {
        String shadowId = "shadow:test-agent:c-multi";
        Task pending = Task.builder().id(shadowId).contextId("c-multi")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id",
                        "rt-old", "_stream_mode", "sse"))
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending);
        // Remote still needs input on resume, carrying a fresh remote task id.
        var rie = new A2ARemoteAgentClient.RemoteInputRequiredException("need more", "rt-new");
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.failedFuture(rie));
        QueryStreamObserver observer = mock(QueryStreamObserver.class);

        orchestrator.streamQuery(req("c-multi"), observer);

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore, atLeastOnce()).save(taskCaptor.capture(), anyBoolean());
        Task resaved = taskCaptor.getValue();
        assertThat(resaved.metadata()).containsEntry("_remote_task_id", "rt-new");
        assertThat(resaved.metadata()).containsEntry("_stream_mode", "sse");
        verify(observer).onNext(argThat(chunk -> QueryChunk.TYPE_INTERRUPT.equals(chunk.getType())));
        verify(observer).onComplete();
    }

    @Test
    void queryDelegateWithSseModeUsesStreamingRemoteCall() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(registry.resolveUrl("test")).thenReturn("http://remote/a2a/");
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.completedFuture("remote result"));
        when(agentHandler.query(any()))
                .thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(Map.of("role", "assistant", "_interrupt",
                        Map.of("message", "delegate", "toolCallId", "call-1", "toolName", "delegate_to_test", "context",
                                Map.of("_interrupt_kind", "a2a_delegate", "agentName", "test", "_stream_mode", "sse"))),
                        "c-query-sse"))
                .thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(
                        Map.of("role", "assistant", "content", "final answer"), "c-query-sse"));

        ServeRequest request = req("c-query-sse");
        request.setMetadata(Map.of("scope", "params"));
        request.setMessages(
                List.of(Map.of("role", "user", "content", "question", "metadata", Map.of("scope", "message"))));

        orchestrator.query(request);

        verify(a2aClient).callStreaming(argThat(c -> "test".equals(c.agentName()) && "delegate".equals(c.message())
                && "c-query-sse".equals(c.contextId()) && "params".equals(c.metadata().get("scope"))
                && "message".equals(c.messageMetadata().get("scope"))), any());
        verify(a2aClient, never()).callSync(any());
        ArgumentCaptor<ServeRequest> requestCaptor = ArgumentCaptor.forClass(ServeRequest.class);
        verify(agentHandler, atLeastOnce()).query(requestCaptor.capture());
        ServeRequest resumed = requestCaptor.getAllValues().get(requestCaptor.getAllValues().size() - 1);
        assertThat(resumed.lastUserMessageMetadata()).containsExactlyEntriesOf(Map.of("scope", "message"));
    }

    @Test
    void queryRemoteTimeoutResumesParentWithStructuredError() {
        when(taskStore.get(anyString())).thenReturn(null);
        var failure = new A2ARemoteAgentClient.RemoteAgentException(A2ARemoteAgentClient.CODE_REMOTE_TIMEOUT,
                "timed out", null);
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.failedFuture(failure));
        when(agentHandler.query(any()))
                .thenReturn(
                        new com.openjiuwen.service.spec.dto.QueryResponse(Map.of("role", "assistant", "_interrupt",
                                Map.of("message", "delegate", "toolCallId", "call-1", "toolName", "delegate_to_test",
                                        "context",
                                        Map.of("_interrupt_kind", "a2a_delegate", "agentName", "test", "_stream_mode",
                                                "sse"))),
                                "c-query-timeout"),
                        new com.openjiuwen.service.spec.dto.QueryResponse(
                                Map.of("role", "assistant", "content", "fallback answer"), "c-query-timeout"));

        var response = orchestrator.query(req("c-query-timeout"));

        assertThat(response.getResult()).isEqualTo(Map.of("role", "assistant", "content", "fallback answer"));
        ArgumentCaptor<ServeRequest> requestCaptor = ArgumentCaptor.forClass(ServeRequest.class);
        verify(agentHandler, atLeastOnce()).query(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues().get(1).lastUserQuery()).contains("REMOTE_TIMEOUT");
        verify(taskStore, never()).save(any(), anyBoolean());
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryRemoteInputRequiredUsesRemoteMessageAsContent() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(registry.resolveUrl("test")).thenReturn("http://remote/a2a/");
        var rie = new A2ARemoteAgentClient.RemoteInputRequiredException("remote needs confirmation", "rt-remote");
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.failedFuture(rie));
        when(agentHandler.query(any()))
                .thenReturn(
                        new com.openjiuwen.service.spec.dto.QueryResponse(
                                Map.of("role", "assistant", "content", "internal delegate prompt", "_interrupt",
                                        Map.of("message", "internal delegate prompt", "toolCallId", "call-1",
                                                "toolName", "delegate_to_test", "context", Map.of("_interrupt_kind",
                                                        "a2a_delegate", "agentName", "test", "_stream_mode", "sse"))),
                                "c-query-remote-input"));

        var response = orchestrator.query(req("c-query-remote-input"));

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertThat(result).containsEntry("content", "remote needs confirmation");
        assertThat((Map<String, Object>) result.get("_interrupt")).containsEntry("message",
                "remote needs confirmation");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryPendingResumeInputRequiredUsesRemoteMessageAsContent() {
        String shadowId = "shadow:test-agent:c-query-pending-input";
        Task pending = Task.builder().id(shadowId).contextId("c-query-pending-input")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                        "_stream_mode", "sse"))
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending);
        var rie = new A2ARemoteAgentClient.RemoteInputRequiredException("please provide order id", "rt-2");
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.failedFuture(rie));

        var response = orchestrator.query(req("c-query-pending-input"));

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertThat(result).containsEntry("content", "please provide order id");
        assertThat((Map<String, Object>) result.get("_interrupt")).containsEntry("message", "please provide order id");
    }

    @Test
    void queryPendingInputKeepsOldRemoteTaskId() {
        String shadowId = "shadow:test-agent:c-query-pending-input-empty-id";
        Task pending = Task.builder().id(shadowId).contextId("c-query-pending-input-empty-id")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                        "_stream_mode", "sse"))
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending);
        var rie = new A2ARemoteAgentClient.RemoteInputRequiredException("please provide order id", "");
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.failedFuture(rie));

        orchestrator.query(req("c-query-pending-input-empty-id"));

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore, atLeastOnce()).save(taskCaptor.capture(), anyBoolean());
        Task resaved = taskCaptor.getValue();
        assertThat(resaved.metadata()).containsEntry("_remote_task_id", "rt-1");
        assertThat(resaved.metadata()).containsEntry("_stream_mode", "sse");
    }

    @Test
    void pendingResumeStreamClosedDeletesShadowAndResumesParent() {
        String shadowId = "shadow:test-agent:c-pending-stream-closed";
        Task pending = Task.builder().id(shadowId).contextId("c-pending-stream-closed")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                        "_stream_mode", "sse"))
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        var failure = new A2ARemoteAgentClient.RemoteAgentException(A2ARemoteAgentClient.CODE_REMOTE_STREAM_CLOSED,
                "stream closed", null);
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.failedFuture(failure));
        QueryStreamObserver observer = mock(QueryStreamObserver.class);

        orchestrator.streamQuery(req("c-pending-stream-closed"), observer);

        verify(taskStore).delete(shadowId);
        verify(taskStore, never()).save(any(), anyBoolean());
        ArgumentCaptor<ServeRequest> requestCaptor = ArgumentCaptor.forClass(ServeRequest.class);
        verify(agentHandler).streamQuery(requestCaptor.capture(), any());
        assertThat(requestCaptor.getValue().lastUserQuery()).contains("REMOTE_STREAM_CLOSED");
    }

    @Test
    void pendingResumeFailureDeletesShadowAndFailsStream() {
        String shadowId = "shadow:test-agent:c-pending-fail";
        Task pending = Task.builder().id(shadowId).contextId("c-pending-fail")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                        "_stream_mode", "sse"))
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending);
        when(a2aClient.callStreaming(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("remote failed")));
        QueryStreamObserver observer = mock(QueryStreamObserver.class);

        orchestrator.streamQuery(req("c-pending-fail"), observer);

        verify(taskStore).delete(shadowId);
        verify(taskStore, never()).save(any(), anyBoolean());
        verifyRemoteFailure(observer);
    }

    @Test
    void pendingResumeWithMissingMetadataDoesNotCrash() {
        String shadowId = "shadow:test-agent:c-pending-no-meta";
        Task pending = Task.builder().id(shadowId).contextId("c-pending-no-meta")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now())).metadata(null)
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        when(a2aClient.callSync(any())).thenReturn("42");
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        orchestrator.streamQuery(req("c-pending-no-meta"), mock(QueryStreamObserver.class));

        verify(a2aClient).callSync(argThat(call -> "".equals(call.agentName())
                && "c-pending-no-meta".equals(call.contextId()) && "".equals(call.taskId())));
    }

    @Test
    void sseDelegateStreamClosedResumesParentWithStructuredError() {
        when(taskStore.get(anyString())).thenReturn(null);
        var failure = new A2ARemoteAgentClient.RemoteAgentException(A2ARemoteAgentClient.CODE_REMOTE_STREAM_CLOSED,
                "stream closed", null);
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.failedFuture(failure));
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            if (firstCall.getAndSet(false)) {
                obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", "delegate", "context",
                        Map.of("_interrupt_kind", "a2a_delegate", "agentName", "test", "_stream_mode", "sse"))));
            } else {
                obs.onComplete();
            }
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        orchestrator.streamQuery(req("c-delegate-fail"), observer);

        verify(taskStore, never()).save(any(), anyBoolean());
        ArgumentCaptor<ServeRequest> requestCaptor = ArgumentCaptor.forClass(ServeRequest.class);
        verify(agentHandler, atLeastOnce()).streamQuery(requestCaptor.capture(), any());
        assertThat(requestCaptor.getAllValues().get(1).lastUserQuery()).contains("REMOTE_STREAM_CLOSED");
    }

    @Test
    void sseDelegateRemoteErrorDeletesShadowAndFailsStream() {
        when(taskStore.get(anyString())).thenReturn(null);
        var failure = new A2ARemoteAgentClient.RemoteAgentException(A2ARemoteAgentClient.CODE_REMOTE_ERROR,
                "remote failed", null);
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.failedFuture(failure));
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", "delegate", "context",
                    Map.of("_interrupt_kind", "a2a_delegate", "agentName", "test", "_stream_mode", "sse"))));
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        orchestrator.streamQuery(req("c-delegate-remote-error"), observer);

        verify(taskStore).delete("shadow:test-agent:c-delegate-remote-error");
        verify(taskStore, never()).save(any(), anyBoolean());
        verifyRemoteFailure(observer);
    }

    @Test
    void querySseInterruptedDeletesShadowAndThrows() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(registry.resolveUrl("test")).thenReturn("http://remote/a2a/");
        CompletableFuture<String> interrupted = new CompletableFuture<>() {
            @Override
            public String get() throws InterruptedException {
                throw new InterruptedException("cancelled");
            }
        };
        when(a2aClient.callStreaming(any(), any())).thenReturn(interrupted);
        when(agentHandler.query(any()))
                .thenReturn(
                        new com.openjiuwen.service.spec.dto.QueryResponse(
                                Map.of("role", "assistant", "_interrupt",
                                        Map.of("message", "delegate", "toolCallId", "call-1", "toolName",
                                                "delegate_to_test", "context", Map.of("_interrupt_kind", "a2a_delegate",
                                                        "agentName", "test", "_stream_mode", "sse"))),
                                "c-query-interrupted"));
        assertThatThrownBy(() -> orchestrator.query(req("c-query-interrupted")))
                .isInstanceOf(A2ARemoteAgentClient.RemoteAgentException.class)
                .hasMessageContaining("Remote agent 'test' call failed");
        verify(taskStore).delete("shadow:test-agent:c-query-interrupted");
        verify(taskStore, never()).save(any(), anyBoolean());
    }

    @Test
    void syncDelegateFailureDeletesShadowAndFailsStream() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(a2aClient.callSync(any()))
                .thenThrow(new A2ARemoteAgentClient.RemoteAgentException("remote failed", new IllegalStateException()));
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", "delegate", "context",
                    Map.of("_interrupt_kind", "a2a_delegate", "agentName", "test"))));
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);

        orchestrator.streamQuery(req("c-sync-delegate-fail"), observer);

        verify(taskStore).delete("shadow:test-agent:c-sync-delegate-fail");
        verify(taskStore, never()).save(any(), anyBoolean());
        verifyRemoteFailure(observer);
    }

    @Test
    void remoteFailureSignalsErrorWhenErrorChunkDeliveryFails() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(a2aClient.callSync(any()))
                .thenThrow(new A2ARemoteAgentClient.RemoteAgentException("remote failed", new IllegalStateException()));
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", "delegate", "context",
                    Map.of("_interrupt_kind", "a2a_delegate", "agentName", "test"))));
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        doThrow(new IllegalStateException("client disconnected")).when(observer).onNext(any());

        assertThatThrownBy(() -> orchestrator.streamQuery(req("c-notify-fail"), observer))
                .isInstanceOf(IllegalStateException.class).hasMessage("client disconnected");
        verify(taskStore).delete("shadow:test-agent:c-notify-fail");
        verify(observer).onError(any(A2ARemoteAgentClient.RemoteAgentException.class));
        verify(observer, never()).onComplete();
    }

    @Test
    void queryPendingResumeFailureDeletesShadowAndThrows() {
        String shadowId = "shadow:test-agent:c-query-pending-fail";
        Task pending = Task.builder().id(shadowId).contextId("c-query-pending-fail")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_agent_name", "test", "_remote_task_id", "rt-1", "_stream_mode", "sse")).build();
        when(taskStore.get(shadowId)).thenReturn(pending);
        when(a2aClient.callStreaming(any(), any()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("sensitive remote detail")));

        assertThatThrownBy(() -> orchestrator.query(req("c-query-pending-fail")))
                .isInstanceOf(A2ARemoteAgentClient.RemoteAgentException.class)
                .hasMessage("Remote agent 'test' call failed");
        verify(taskStore).delete(shadowId);
        verify(taskStore, never()).save(any(), anyBoolean());
    }

    @Test
    void queryPendingResumeWithSseModeUsesStreamingRemoteCall() {
        String shadowId = "shadow:test-agent:c-query-pending-sse";
        Task pending = Task.builder().id(shadowId).contextId("c-query-pending-sse")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                        "_stream_mode", "sse"))
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        when(a2aClient.callStreaming(any(), any())).thenReturn(CompletableFuture.completedFuture("remote result"));
        when(agentHandler.query(any())).thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(
                Map.of("role", "assistant", "content", "final answer"), "c-query-pending-sse"));

        orchestrator.query(req("c-query-pending-sse"));

        verify(a2aClient).callStreaming(argThat(c -> "test".equals(c.agentName())
                && "c-query-pending-sse".equals(c.contextId()) && "rt-1".equals(c.taskId())), any());
        verify(a2aClient, never()).callSync(any());
    }

    @Test
    void queryPendingResumePreservesOriginalStreamFlag() {
        String shadowId = "shadow:test-agent:c-query-sync-resume";
        Task pending = Task.builder().id(shadowId).contextId("c-query-sync-resume")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1"))
                .build();
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        when(a2aClient.callSync(any())).thenReturn("remote result");
        when(agentHandler.query(any())).thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(
                Map.of("role", "assistant", "content", "final answer"), "c-query-sync-resume"));
        ServeRequest request = req("c-query-sync-resume");
        request.setStream(false);

        orchestrator.query(request);

        verify(agentHandler).query(argThat(resume -> !resume.isStream()));
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
                Task.builder().id("t1").contextId("c-reset").status(new TaskStatus(TaskState.TASK_STATE_COMPLETED))
                        .build(),
                Task.builder().id("t2").contextId("c-reset").status(new TaskStatus(TaskState.TASK_STATE_WORKING))
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

    private static ServeRequest req(String convId) {
        ServeRequest r = new ServeRequest();
        r.setConversationId(convId);
        r.setStream(true);
        return r;
    }

    private static void verifyRemoteFailure(QueryStreamObserver observer) {
        verify(observer).onNext(argThat(chunk -> QueryChunk.TYPE_ERROR.equals(chunk.getType())
                && chunk.getData() instanceof Map<?, ?> body && "REMOTE_A2A_CALL_FAILED".equals(body.get("code"))
                && !String.valueOf(body).contains("remote failed")));
        verify(observer).onError(any(A2ARemoteAgentClient.RemoteAgentException.class));
        verify(observer, never()).onComplete();
    }

    private static AgentCard testCard() {
        return AgentCard.builder().name("test-agent").description("test").version("1.0")
                .capabilities(new AgentCapabilities(true, false, false, List.of())).defaultInputModes(List.of())
                .defaultOutputModes(List.of()).skills(List.of()).securitySchemes(Map.of())
                .securityRequirements(List.of())
                .supportedInterfaces(List.of(new AgentInterface("jsonrpc", "http://remote/a2a/", null, "1.0")))
                .signatures(List.of()).additionalInterfaces(List.of()).build();
    }
}
