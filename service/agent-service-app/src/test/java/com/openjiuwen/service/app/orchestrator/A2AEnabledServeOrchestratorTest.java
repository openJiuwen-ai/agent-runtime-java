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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentException;
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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unit tests for the A2A-enabled serve orchestrator.
 */
class A2AEnabledServeOrchestratorTest {
    private AgentHandler agentHandler;

    private TaskStore taskStore;

    private RemoteAgentCardResolver cardResolver;

    private ActiveStreamRegistry streamRegistry;

    private RemoteAgentCaller remoteAgentCaller;

    private A2AEnabledServeOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        agentHandler = mock(AgentHandler.class);
        taskStore = mock(TaskStore.class);
        remoteAgentCaller = mock(RemoteAgentCaller.class);
        cardResolver = mock(RemoteAgentCardResolver.class);
        streamRegistry = mock(ActiveStreamRegistry.class);
        when(streamRegistry.register(anyString())).thenReturn(mock(StreamCancellationHandle.class));
        orchestrator = new A2AEnabledServeOrchestrator(agentHandler, taskStore, remoteAgentCaller, cardResolver,
            streamRegistry, "test-agent");
    }

    /**
     * Stubs the remote caller to emit a single answer-envelope chunk and complete,
     * producing the captured answer text {@code answer}.
     */
    private void stubRemoteAnswer(String answer) {
        String envelope = "{\"type\":\"answer\",\"payload\":{\"content\":" + jsonQuote(answer) + "}}";
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope));
            obs.onComplete();
            return null;
        }).when(remoteAgentCaller).call(any(RemoteAgentCall.class), any(QueryStreamObserver.class));
    }

    /**
     * Stubs the remote caller to emit an INPUT_REQUIRED interrupt carrying the given
     * remote task id, then complete.
     */
    private void stubRemoteInputRequired(String message, String remoteTaskId) {
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                Map.of("message", message, "remote_task_id", remoteTaskId)));
            obs.onComplete();
            return null;
        }).when(remoteAgentCaller).call(any(RemoteAgentCall.class), any(QueryStreamObserver.class));
    }

    /**
     * Stubs the remote caller to signal an error via {@code observer.onError}.
     */
    private void stubRemoteFailure(Throwable err) {
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onError(err);
            return null;
        }).when(remoteAgentCaller).call(any(RemoteAgentCall.class), any(QueryStreamObserver.class));
    }

    private static String jsonQuote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.append('"').toString();
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
    void a2aDelegateRemoteFailureDeletesShadowAndSignalsError() {
        when(taskStore.list(any())).thenReturn(new ListTasksResult(List.of()));

        // Setup: agent produces a2a_interrupt chunk
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(
                new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("agentName", "hotel-agent", "toolName", "search")));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        when(cardResolver.resolveJsonRpcUrl(anyString())).thenReturn("http://remote/a2a/");
        // Remote delegation fails → orchestrator must delete any shadow task
        // and signal the error to the client observer.
        stubRemoteFailure(new IllegalStateException("remote unavailable"));

        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        orchestrator.streamQuery(req("c-int"), observer);

        verify(taskStore, atLeastOnce()).delete(eq("shadow:test-agent:c-int"));
        verify(observer).onNext(any(QueryChunk.class));
        verify(observer).onError(any(Throwable.class));
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
        Task pending = Task.builder()
            .id(shadowId)
            .contextId("c-pending")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
            .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id",
                "remote-task-123"))
            .build();
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
        Task pending = Task.builder()
            .id(shadowId)
            .contextId("c-sync")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
            .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1"))
            .build();
        // Deleted after a successful resume, so the second findPending sees nothing.
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        stubRemoteAnswer("42");

        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        orchestrator.streamQuery(req("c-sync"), observer);

        // No _stream_mode → resolve synchronously. The caller always receives a
        // wrapper observer (used to capture the answer text), but in sync mode the
        // wrapper's passthrough is null so the client observer never sees remote
        // chunks. Verify the caller was invoked with the right coordinates and that
        // the client observer received no chunks.
        verify(remoteAgentCaller).call(argThat((RemoteAgentCall c) -> "test".equals(c.agentId())
            && "c-sync".equals(c.contextId()) && "rt-1".equals(c.taskId())), any());
        verify(observer, never()).onNext(any());
    }

    @Test
    void pendingResumeWithSseModeStreamsThroughObserver() {
        String shadowId = "shadow:test-agent:c-sse";
        Task pending = Task.builder()
            .id(shadowId)
            .contextId("c-sse")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
            .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                "_stream_mode", "sse"))
            .build();
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        stubRemoteAnswer("42");

        orchestrator.streamQuery(req("c-sse"), mock(QueryStreamObserver.class));

        // _stream_mode=sse → stream the remote content to the client observer
        // (passthrough non-null).
        verify(remoteAgentCaller).call(argThat((RemoteAgentCall c) -> "test".equals(c.agentId())
            && "c-sse".equals(c.contextId())), argThat(o -> o != null));
    }

    @Test
    void resumeInputRequiredCompletesObserver() throws Exception {
        String shadowId = "shadow:test-agent:c-multi";
        Task pending = Task.builder()
            .id(shadowId)
            .contextId("c-multi")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
            .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-old",
                "_stream_mode", "sse"))
            .build();
        when(taskStore.get(shadowId)).thenReturn(pending);
        // Remote still needs input on resume, carrying a fresh remote task id.
        stubRemoteInputRequired("need more", "rt-new");
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
        when(cardResolver.resolveJsonRpcUrl("test")).thenReturn("http://remote/a2a/");
        stubRemoteAnswer("remote result");
        when(agentHandler.query(any())).thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(
                Map.of("role", "assistant", "_interrupt",
                    Map.of("message", "delegate", "toolCallId", "call-1", "toolName", "delegate_to_test", "context",
                        Map.of("_interrupt_kind", "a2a_delegate", "agentName", "test", "_stream_mode", "sse"))),
                "c-query-sse"))
            .thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(
                Map.of("role", "assistant", "content", "final answer"), "c-query-sse"));

        orchestrator.query(req("c-query-sse"));

        verify(remoteAgentCaller).call(argThat((RemoteAgentCall c) -> "test".equals(c.agentId())
            && "delegate".equals(c.message()) && "c-query-sse".equals(c.contextId())), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryRemoteInputRequiredUsesRemoteMessageAsContent() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(cardResolver.resolveJsonRpcUrl("test")).thenReturn("http://remote/a2a/");
        stubRemoteInputRequired("remote needs confirmation", "rt-remote");
        when(agentHandler.query(any())).thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(
            Map.of("role", "assistant", "content", "internal delegate prompt", "_interrupt",
                Map.of("message", "internal delegate prompt", "toolCallId", "call-1", "toolName", "delegate_to_test",
                    "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "test", "_stream_mode", "sse"))),
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
        Task pending = Task.builder()
            .id(shadowId)
            .contextId("c-query-pending-input")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
            .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                "_stream_mode", "sse"))
            .build();
        when(taskStore.get(shadowId)).thenReturn(pending);
        stubRemoteInputRequired("please provide order id", "rt-2");

        var response = orchestrator.query(req("c-query-pending-input"));

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertThat(result).containsEntry("content", "please provide order id");
        assertThat((Map<String, Object>) result.get("_interrupt")).containsEntry("message", "please provide order id");
    }

    @Test
    void queryPendingInputKeepsOldRemoteTaskId() {
        String shadowId = "shadow:test-agent:c-query-pending-input-empty-id";
        Task pending = Task.builder()
            .id(shadowId)
            .contextId("c-query-pending-input-empty-id")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
            .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                "_stream_mode", "sse"))
            .build();
        when(taskStore.get(shadowId)).thenReturn(pending);
        // Remote returns INPUT_REQUIRED with an empty remote task id → orchestrator
        // must keep the previous rt-1.
        stubRemoteInputRequired("please provide order id", "");

        orchestrator.query(req("c-query-pending-input-empty-id"));

        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore, atLeastOnce()).save(taskCaptor.capture(), anyBoolean());
        Task resaved = taskCaptor.getValue();
        assertThat(resaved.metadata()).containsEntry("_remote_task_id", "rt-1");
        assertThat(resaved.metadata()).containsEntry("_stream_mode", "sse");
    }

    @Test
    void pendingResumeFailureDeletesShadowAndFailsStream() {
        String shadowId = "shadow:test-agent:c-pending-fail";
        Task pending = Task.builder()
            .id(shadowId)
            .contextId("c-pending-fail")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
            .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                "_stream_mode", "sse"))
            .build();
        when(taskStore.get(shadowId)).thenReturn(pending);
        stubRemoteFailure(new IllegalStateException("remote failed"));

        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        orchestrator.streamQuery(req("c-pending-fail"), observer);

        verify(taskStore, atLeastOnce()).delete(eq(shadowId));
        verify(observer).onError(any(Throwable.class));
    }

    @Test
    void pendingResumeWithMissingMetadataDoesNotCrash() {
        String shadowId = "shadow:test-agent:c-pending-no-meta";
        Task pending = Task.builder()
            .id(shadowId)
            .contextId("c-pending-no-meta")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
            .metadata(null)
            .build();
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        stubRemoteAnswer("42");
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        // Empty agent name makes RemoteAgentCall construction invalid; the
        // orchestrator must catch the failure, delete the shadow task, and
        // signal the error without crashing.
        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        orchestrator.streamQuery(req("c-pending-no-meta"), observer);

        verify(remoteAgentCaller, never()).call(any(RemoteAgentCall.class), any());
        verify(taskStore, atLeastOnce()).delete(eq(shadowId));
        verify(observer).onError(any(Throwable.class));
    }

    @Test
    void sseDelegateFailureDeletesShadowAndFailsStream() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(cardResolver.resolveJsonRpcUrl("test")).thenReturn("http://remote/a2a/");
        stubRemoteFailure(new IllegalStateException("remote failed"));
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of("message", "delegate", "context",
                Map.of("_interrupt_kind", "a2a_delegate", "agentName", "test", "_stream_mode", "sse"))));
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        QueryStreamObserver observer = mock(QueryStreamObserver.class);
        orchestrator.streamQuery(req("c-delegate-fail"), observer);

        verify(taskStore, atLeastOnce()).delete(eq("shadow:test-agent:c-delegate-fail"));
        verify(observer).onError(any(Throwable.class));
    }

    @Test
    void querySseInterruptedDeletesShadowAndThrows() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(cardResolver.resolveJsonRpcUrl("test")).thenReturn("http://remote/a2a/");
        // Simulate remote failure (legacy InterruptedException mapped to onError).
        stubRemoteFailure(new IllegalStateException("remote failed"));
        when(agentHandler.query(any())).thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(
            Map.of("role", "assistant", "_interrupt",
                Map.of("message", "delegate", "toolCallId", "call-1", "toolName", "delegate_to_test", "context",
                    Map.of("_interrupt_kind", "a2a_delegate", "agentName", "test", "_stream_mode", "sse"))),
            "c-query-interrupted"));
        boolean isInterrupted = Thread.interrupted();
        assertThat(isInterrupted).isFalse();

        assertThatThrownBy(() -> orchestrator.query(req("c-query-interrupted")))
            .isInstanceOf(RemoteAgentException.class);

        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        verify(taskStore, atLeastOnce()).delete(eq("shadow:test-agent:c-query-interrupted"));
    }

    @Test
    void queryPendingResumeWithSseModeUsesStreamingRemoteCall() {
        String shadowId = "shadow:test-agent:c-query-pending-sse";
        Task pending = Task.builder()
            .id(shadowId)
            .contextId("c-query-pending-sse")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
            .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1",
                "_stream_mode", "sse"))
            .build();
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        stubRemoteAnswer("remote result");
        when(agentHandler.query(any())).thenReturn(
            new com.openjiuwen.service.spec.dto.QueryResponse(Map.of("role", "assistant", "content", "final answer"),
                "c-query-pending-sse"));

        orchestrator.query(req("c-query-pending-sse"));

        verify(remoteAgentCaller).call(argThat((RemoteAgentCall c) -> "test".equals(c.agentId())
            && "c-query-pending-sse".equals(c.contextId()) && "rt-1".equals(c.taskId())), any());
    }

    @Test
    void queryPendingResumePreservesOriginalStreamFlag() {
        String shadowId = "shadow:test-agent:c-query-sync-resume";
        Task pending = Task.builder()
            .id(shadowId)
            .contextId("c-query-sync-resume")
            .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
            .metadata(Map.of("_remote_url", "http://remote/a2a/", "_agent_name", "test", "_remote_task_id", "rt-1"))
            .build();
        when(taskStore.get(shadowId)).thenReturn(pending).thenReturn(null);
        stubRemoteAnswer("remote result");
        when(agentHandler.query(any())).thenReturn(
            new com.openjiuwen.service.spec.dto.QueryResponse(Map.of("role", "assistant", "content", "final answer"),
                "c-query-sync-resume"));
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

    private static ServeRequest req(String convId) {
        ServeRequest r = new ServeRequest();
        r.setConversationId(convId);
        r.setStream(true);
        return r;
    }

    @Test
    void queryA2aDelegateForwardsResponseContentToRemoteCaller() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(cardResolver.resolveJsonRpcUrl("L2-agent")).thenReturn("http://remote/a2a/");
        stubRemoteAnswer("remote final");
        when(agentHandler.query(any())).thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(
            Map.of("role", "assistant", "_interrupt",
                Map.of("message", "user query", "responseContent", "L1 assistant output", "resume", false, "context",
                    Map.of("_interrupt_kind", "a2a_delegate", "agentName", "L2-agent", "_stream_mode", ""))),
            "c-response-content"));

        orchestrator.query(req("c-response-content"));

        // a2a_delegate interrupt carrying responseContent → RemoteAgentCall must
        // propagate it so the caller can append it as an assistant message.
        verify(remoteAgentCaller).call(argThat((RemoteAgentCall c) -> "L2-agent".equals(c.agentId())
            && "L1 assistant output".equals(c.responseContent())
            && "user query".equals(c.message())), any());
        // resume=false → orchestrator must NOT re-invoke the agent with the remote answer.
        verify(agentHandler).query(any());
    }

    @Test
    void queryA2aDelegateResumeReinvokesAgentWithRemoteAnswer() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(cardResolver.resolveJsonRpcUrl("L2-agent")).thenReturn("http://remote/a2a/");
        stubRemoteAnswer("remote final");
        when(agentHandler.query(any())).thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(
            Map.of("role", "assistant", "_interrupt",
                // No "resume" field → defaults to true (tool-call path).
                Map.of("message", "user query", "responseContent", "L1 tool output", "context",
                    Map.of("_interrupt_kind", "a2a_delegate", "agentName", "L2-agent", "_stream_mode", ""))),
            "c-resume"))
            .thenReturn(new com.openjiuwen.service.spec.dto.QueryResponse(
                Map.of("role", "assistant", "content", "final answer"), "c-resume"));

        com.openjiuwen.service.spec.dto.QueryResponse response = orchestrator.query(req("c-resume"));

        verify(remoteAgentCaller).call(argThat((RemoteAgentCall c) -> "L2-agent".equals(c.agentId())
            && "L1 tool output".equals(c.responseContent())), any());
        // resume=true → orchestrator re-invokes the agent with the remote answer as a tool result.
        verify(agentHandler, atLeastOnce()).query(any());
        assertThat(response.getResult()).isInstanceOf(java.util.Map.class);
        assertThat(((java.util.Map<?, ?>) response.getResult()).get("content")).isEqualTo("final answer");
    }

    @Test
    void streamA2aDelegateForwardsResponseContentToRemoteCaller() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(cardResolver.resolveJsonRpcUrl("L2-agent")).thenReturn("http://remote/a2a/");
        stubRemoteAnswer("remote final");
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of(
                "message", "user query",
                "responseContent", "L1 streaming output",
                "resume", false,
                "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "L2-agent", "_stream_mode", "sse"))));
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        orchestrator.streamQuery(req("c-stream-response"), mock(QueryStreamObserver.class));

        verify(remoteAgentCaller).call(argThat((RemoteAgentCall c) -> "L2-agent".equals(c.agentId())
            && "L1 streaming output".equals(c.responseContent())
            && c.streaming()), any());
        // resume=false → agent handler invoked only once.
        verify(agentHandler).streamQuery(any(), any());
    }

    @Test
    void streamA2aDelegateResumeReinvokesAgentWithRemoteAnswer() {
        when(taskStore.get(anyString())).thenReturn(null);
        when(cardResolver.resolveJsonRpcUrl("L2-agent")).thenReturn("http://remote/a2a/");
        stubRemoteAnswer("remote final");
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            if (firstCall.compareAndSet(true, false)) {
                obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT, Map.of(
                    "message", "user query",
                    "responseContent", "L1 streaming output",
                    // No "resume" field → defaults to true (tool-call path).
                    "context", Map.of("_interrupt_kind", "a2a_delegate", "agentName", "L2-agent", "_stream_mode", "sse"))));
            } else {
                obs.onNext(new QueryChunk("chunk", "done"));
                obs.onComplete();
            }
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        orchestrator.streamQuery(req("c-stream-resume"), mock(QueryStreamObserver.class));

        verify(remoteAgentCaller).call(argThat((RemoteAgentCall c) -> "L2-agent".equals(c.agentId())
            && "L1 streaming output".equals(c.responseContent())
            && c.streaming()), any());
        // resume=true → orchestrator re-invokes the agent with the remote answer.
        verify(agentHandler, atLeastOnce()).streamQuery(any(), any());
    }

    private static AgentCard testCard() {
        return AgentCard.builder()
            .name("test-agent")
            .description("test")
            .version("1.0")
            .capabilities(new AgentCapabilities(true, false, false, List.of()))
            .defaultInputModes(List.of())
            .defaultOutputModes(List.of())
            .skills(List.of())
            .securitySchemes(Map.of())
            .securityRequirements(List.of())
            .supportedInterfaces(List.of(new AgentInterface("jsonrpc", "http://remote/a2a/", null, "1.0")))
            .signatures(List.of())
            .additionalInterfaces(List.of())
            .build();
    }
}
