/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for the orchestrator's generic forward-execution path driven by
 * {@link ServeForwardStrategy}. Verifies that {@code executeForwardSync} /
 * {@code executeForwardStream} correctly translate remote success,
 * INPUT_REQUIRED, and error outcomes — and that INPUT_REQUIRED saves a shadow
 * task so the next resume targets the right remote task.
 *
 * <p>The strategy is mocked to return a {@link RemoteAgentCall}; the test
 * exercises the orchestrator's generic execution machinery, not any
 * Versatile-specific envelope detection.
 */
class A2AEnabledServeOrchestratorForwardStrategyTest {
    private AgentHandler agentHandler;

    private TaskStore taskStore;

    private RemoteAgentCaller remoteAgentCaller;

    private RemoteAgentCardResolver cardResolver;

    private ActiveStreamRegistry streamRegistry;

    private ServeForwardStrategy forwardStrategy;

    @BeforeEach
    void setUp() {
        agentHandler = mock(AgentHandler.class);
        taskStore = mock(TaskStore.class);
        remoteAgentCaller = mock(RemoteAgentCaller.class);
        cardResolver = mock(RemoteAgentCardResolver.class);
        streamRegistry = mock(ActiveStreamRegistry.class);
        forwardStrategy = mock(ServeForwardStrategy.class);
        when(streamRegistry.register(anyString())).thenReturn(mock(StreamCancellationHandle.class));
        when(cardResolver.resolveJsonRpcUrl(anyString())).thenReturn("http://remote/jsonrpc");
    }

    private A2AEnabledServeOrchestrator orchestrator() {
        return new A2AEnabledServeOrchestrator(agentHandler, taskStore, remoteAgentCaller, cardResolver,
                streamRegistry, "test-agent", forwardStrategy);
    }

    private ServeRequest request(boolean stream) {
        ServeRequest req = new ServeRequest();
        req.setConversationId("c-1");
        req.setStream(stream);
        req.setMessages(List.of(Map.of("role", "user", "content", "hi")));
        return req;
    }

    private void stubRemoteAnswer(String answer, String agentId, String intentId) {
        String envelope = "{\"type\":\"answer\",\"payload\":{\"content\":" + jsonQuote(answer)
                + "},\"agent_id\":" + jsonQuote(agentId) + ",\"intent_id\":" + jsonQuote(intentId) + "}";
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, envelope));
            obs.onComplete();
            return null;
        }).when(remoteAgentCaller).call(any(RemoteAgentCall.class), any(QueryStreamObserver.class));
    }

    private void stubRemoteInputRequired(String message, String remoteTaskId) {
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                Map.of("message", message, "remote_task_id", remoteTaskId)));
            obs.onComplete();
            return null;
        }).when(remoteAgentCaller).call(any(RemoteAgentCall.class), any(QueryStreamObserver.class));
    }

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
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private void stubShadowTask(String remoteTaskId) {
        Task shadow = Task.builder().id("shadow:test-agent:c-1").contextId("c-1")
                .status(new TaskStatus(TaskState.TASK_STATE_INPUT_REQUIRED, null, OffsetDateTime.now()))
                .metadata(Map.of("_agent_name", "remote-agent", "_remote_task_id", remoteTaskId,
                        "_stream_mode", "sse", "_remote_url", "http://remote/jsonrpc"))
                .build();
        when(taskStore.get("shadow:test-agent:c-1")).thenReturn(shadow);
    }

    // ── sync query() forward ──

    @Test
    void syncForwardWrapsRemoteAnswerIntoResponseWithEnvelopeFields() {
        Map<String, Object> localResult = new java.util.LinkedHashMap<>();
        localResult.put("role", "assistant");
        localResult.put("response_content", "local-output");
        localResult.put("agent_id", "remote-agent");
        when(agentHandler.query(any())).thenReturn(
                new QueryResponse(localResult, "c-1"));
        when(forwardStrategy.evaluateForward(any(), any())).thenAnswer(inv -> {
            QueryResponse resp = inv.getArgument(0);
            ServeRequest req = inv.getArgument(1);
            Map<?, ?> m = (Map<?, ?>) resp.getResult();
            String aid = (String) m.get("agent_id");
            String rc = (String) m.get("response_content");
            return Optional.of(new RemoteAgentCall(aid, req, rc, req.getConversationId(),
                    null, req.lastUserQuery(), false));
        });
        stubRemoteAnswer("remote-answer", "L2-agent", "L2-intent");

        QueryResponse response = orchestrator().query(request(false));

        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertThat(result.get("response_content")).isEqualTo("remote-answer");
        assertThat(result.get("content")).isEqualTo("remote-answer");
        assertThat(result.get("agent_id")).isEqualTo("L2-agent");
        assertThat(result.get("intent_id")).isEqualTo("L2-intent");
    }

    @Test
    void syncForwardInputRequiredSavesShadowTaskAndReturnsInterruptResponse() {
        Map<String, Object> localResult = new java.util.LinkedHashMap<>();
        localResult.put("role", "assistant");
        localResult.put("response_content", "local-output");
        localResult.put("agent_id", "remote-agent");
        when(agentHandler.query(any())).thenReturn(
                new QueryResponse(localResult, "c-1"));
        when(forwardStrategy.evaluateForward(any(), any())).thenAnswer(inv -> {
            QueryResponse resp = inv.getArgument(0);
            ServeRequest req = inv.getArgument(1);
            Map<?, ?> m = (Map<?, ?>) resp.getResult();
            return Optional.of(new RemoteAgentCall((String) m.get("agent_id"), req,
                    (String) m.get("response_content"), req.getConversationId(),
                    null, req.lastUserQuery(), false));
        });
        stubRemoteInputRequired("请补充日期", "rt-L2-1");

        QueryResponse response = orchestrator().query(request(false));

        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertThat(result.get("_interrupt")).isNotNull();
        assertThat(((Map<?, ?>) result.get("_interrupt")).get("message")).isEqualTo("请补充日期");
        // Shadow task MUST be saved so resume can target the right remote task
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).save(taskCaptor.capture(), eq(true));
        Task saved = taskCaptor.getValue();
        assertThat(saved.metadata().get("_remote_task_id")).isEqualTo("rt-L2-1");
        assertThat(saved.metadata().get("_agent_name")).isEqualTo("remote-agent");
    }

    @Test
    void syncForwardErrorFallsBackToLocalResponse() {
        Map<String, Object> localResult = new java.util.LinkedHashMap<>();
        localResult.put("role", "assistant");
        localResult.put("response_content", "local-output");
        localResult.put("agent_id", "remote-agent");
        QueryResponse localResponse = new QueryResponse(localResult, "c-1");
        when(agentHandler.query(any())).thenReturn(localResponse);
        when(forwardStrategy.evaluateForward(any(), any())).thenAnswer(inv -> {
            QueryResponse resp = inv.getArgument(0);
            ServeRequest req = inv.getArgument(1);
            Map<?, ?> m = (Map<?, ?>) resp.getResult();
            return Optional.of(new RemoteAgentCall((String) m.get("agent_id"), req,
                    (String) m.get("response_content"), req.getConversationId(),
                    null, req.lastUserQuery(), false));
        });
        stubRemoteFailure(new RemoteAgentException(RemoteAgentException.CODE_REMOTE_ERROR, "boom", null));

        QueryResponse response = orchestrator().query(request(false));

        // Falls back to local response (same instance)
        assertThat(response).isSameAs(localResponse);
        verify(taskStore, never()).save(any(), eq(true));
    }

    // ── streaming streamQuery() forward ──

    @Test
    void streamForwardStreamsRemoteChunksToClient() {
        when(forwardStrategy.interceptStreamEnvelope(any(), any())).thenAnswer(inv -> {
            QueryChunk chunk = inv.getArgument(0);
            ServeRequest req = inv.getArgument(1);
            if (chunk.getData() instanceof Map<?, ?> m
                    && "answer".equals(m.get("type"))
                    && m.get("agent_id") instanceof String aid && !aid.isBlank()) {
                String rc = m.get("response_content") instanceof String s ? s : null;
                return Optional.of(new RemoteAgentCall(aid, req, rc, req.getConversationId(),
                        null, req.lastUserQuery(), true));
            }
            return Optional.empty();
        });
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of(
                    "type", "answer", "agent_id", "remote-agent", "response_content", "local")));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        stubRemoteAnswer("remote-final", "L2-agent", "L2-intent");

        List<QueryChunk> sink = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        QueryStreamObserver client = sinkObserver(sink, completed);

        orchestrator().streamQuery(request(true), client);

        assertThat(sink).isNotEmpty();
        assertThat(completed).isTrue();
    }

    @Test
    void streamForwardInputRequiredSavesShadowTaskAndSurfacesInterrupt() {
        when(forwardStrategy.interceptStreamEnvelope(any(), any())).thenAnswer(inv -> {
            QueryChunk chunk = inv.getArgument(0);
            ServeRequest req = inv.getArgument(1);
            if (chunk.getData() instanceof Map<?, ?> m
                    && "answer".equals(m.get("type"))
                    && m.get("agent_id") instanceof String aid && !aid.isBlank()) {
                String rc = m.get("response_content") instanceof String s ? s : null;
                return Optional.of(new RemoteAgentCall(aid, req, rc, req.getConversationId(),
                        null, req.lastUserQuery(), true));
            }
            return Optional.empty();
        });
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of(
                    "type", "answer", "agent_id", "remote-agent", "response_content", "local")));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        stubRemoteInputRequired("请补充日期", "rt-L2-1");

        List<QueryChunk> sink = new ArrayList<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        QueryStreamObserver client = sinkObserver(sink, completed);

        orchestrator().streamQuery(request(true), client);

        assertThat(sink).filteredOn(c -> QueryChunk.TYPE_INTERRUPT.equals(c.getType()))
                .singleElement()
                .satisfies(c -> {
                    Map<?, ?> data = (Map<?, ?>) c.getData();
                    assertThat(data.get("message")).isEqualTo("请补充日期");
                    assertThat(data.get("remote_task_id")).isEqualTo("rt-L2-1");
                });
        assertThat(completed).isTrue();
        // Shadow task MUST be saved
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).save(taskCaptor.capture(), eq(true));
        Task saved = taskCaptor.getValue();
        assertThat(saved.metadata().get("_remote_task_id")).isEqualTo("rt-L2-1");
        assertThat(saved.metadata().get("_stream_mode")).isEqualTo("sse");
    }

    @Test
    void streamForwardErrorDoesNotDoubleNotifyWhenWrapperAlreadyForwardedOnError() {
        when(forwardStrategy.interceptStreamEnvelope(any(), any())).thenAnswer(inv -> {
            QueryChunk chunk = inv.getArgument(0);
            ServeRequest req = inv.getArgument(1);
            if (chunk.getData() instanceof Map<?, ?> m
                    && "answer".equals(m.get("type"))
                    && m.get("agent_id") instanceof String aid && !aid.isBlank()) {
                return Optional.of(new RemoteAgentCall(aid, req, null, req.getConversationId(),
                        null, req.lastUserQuery(), true));
            }
            return Optional.empty();
        });
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of(
                    "type", "answer", "agent_id", "remote-agent")));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        stubRemoteFailure(new RuntimeException("remote boom"));

        AtomicReference<Throwable> errorRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        QueryStreamObserver client = new QueryStreamObserver() {
            @Override public void onNext(QueryChunk chunk) { }
            @Override public void onComplete() { completed.set(true); }
            @Override public void onError(Throwable e) { errorRef.set(e); }
            @Override public boolean isCancelled() { return false; }
        };

        orchestrator().streamQuery(request(true), client);

        assertThat(errorRef.get()).isNotNull();
        // Wrapper already forwarded onError; orchestrator must NOT also call onComplete.
        assertThat(completed).isFalse();
    }

    // ── resume after forward INPUT_REQUIRED ──

    @Test
    void resumeAfterForwardInputRequiredTargetsShadowTaskRemoteId() {
        // First call: forward triggers, remote returns INPUT_REQUIRED, shadow task saved
        when(forwardStrategy.interceptStreamEnvelope(any(), any())).thenAnswer(inv -> {
            QueryChunk chunk = inv.getArgument(0);
            ServeRequest req = inv.getArgument(1);
            if (chunk.getData() instanceof Map<?, ?> m
                    && "answer".equals(m.get("type"))
                    && m.get("agent_id") instanceof String aid && !aid.isBlank()) {
                return Optional.of(new RemoteAgentCall(aid, req, null, req.getConversationId(),
                        null, req.lastUserQuery(), true));
            }
            return Optional.empty();
        });
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of(
                    "type", "answer", "agent_id", "remote-agent")));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());
        stubRemoteInputRequired("请补充日期", "rt-L2-1");

        List<QueryChunk> firstSink = new ArrayList<>();
        orchestrator().streamQuery(request(true), sinkObserver(firstSink, new AtomicBoolean(false)));

        // Shadow task now exists with remote_task_id=rt-L2-1
        stubShadowTask("rt-L2-1");
        // Second call: resume should hit findPending → callRemoteAndCapture with taskId=rt-L2-1
        stubRemoteAnswer("resumed-answer", "L2-agent", "L2-intent");

        List<QueryChunk> secondSink = new ArrayList<>();
        AtomicBoolean secondCompleted = new AtomicBoolean(false);
        orchestrator().streamQuery(request(true), sinkObserver(secondSink, secondCompleted));

        ArgumentCaptor<RemoteAgentCall> callCaptor = ArgumentCaptor.forClass(RemoteAgentCall.class);
        verify(remoteAgentCaller, org.mockito.Mockito.atLeast(2)).call(callCaptor.capture(), any());
        // The resume call MUST carry taskId=rt-L2-1 (the saved shadow task's remote id)
        RemoteAgentCall resumeCall = callCaptor.getAllValues().get(1);
        assertThat(resumeCall.taskId()).isEqualTo("rt-L2-1");
        assertThat(secondCompleted).isTrue();
    }

    private QueryStreamObserver sinkObserver(List<QueryChunk> sink, AtomicBoolean completed) {
        return new QueryStreamObserver() {
            @Override public void onNext(QueryChunk chunk) { sink.add(chunk); }
            @Override public void onComplete() { completed.set(true); }
            @Override public void onError(Throwable e) { }
            @Override public boolean isCancelled() { return false; }
        };
    }
}
