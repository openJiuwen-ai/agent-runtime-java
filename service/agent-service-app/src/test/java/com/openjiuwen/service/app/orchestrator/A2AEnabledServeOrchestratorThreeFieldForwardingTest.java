/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCaller;
import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCardResolver;
import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.a2aproject.sdk.server.tasks.TaskStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2AEnabledServeOrchestratorThreeFieldForwardingTest {
    private AgentHandler agentHandler;

    private RemoteAgentCaller caller;

    private RemoteAgentCardResolver resolver;

    private ActiveStreamRegistry streamRegistry;

    @BeforeEach
    void setUp() {
        agentHandler = mock(AgentHandler.class);
        caller = mock(RemoteAgentCaller.class);
        resolver = mock(RemoteAgentCardResolver.class);
        streamRegistry = mock(ActiveStreamRegistry.class);
        when(streamRegistry.register(any())).thenReturn(mock(
                com.openjiuwen.service.app.lifecycle.StreamCancellationHandle.class));
    }

    @Test
    void forwardsToRemoteWhenHandlerReturnsThreeFieldResult() {
        Map<String, Object> handlerResult = new LinkedHashMap<>();
        handlerResult.put("role", "assistant");
        handlerResult.put("response_content", "酒店预订");
        handlerResult.put("intent_id", "intent_L1_hotel");
        handlerResult.put("agent_id", "agent_card_L2_hotel");
        when(agentHandler.query(any())).thenReturn(
                new QueryResponse(handlerResult, "c-1"));

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    Map.of("type", "answer", "output", "downstream result")));
            obs.onComplete();
            return null;
        }).when(caller).call(any(RemoteAgentCall.class), any(QueryStreamObserver.class));

        A2AEnabledServeOrchestrator orchestrator = new A2AEnabledServeOrchestrator(
                agentHandler, mock(TaskStore.class), caller, resolver, streamRegistry, "agent-L1");

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setStream(false);
        request.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));

        QueryResponse response = orchestrator.query(request);

        ArgumentCaptor<RemoteAgentCall> callCaptor = ArgumentCaptor.forClass(RemoteAgentCall.class);
        verify(caller).call(callCaptor.capture(), any());
        assertThat(callCaptor.getValue().agentId()).isEqualTo("agent_card_L2_hotel");
        assertThat(callCaptor.getValue().responseContent()).isEqualTo("酒店预订");
        assertThat(callCaptor.getValue().serveRequest()).isSameAs(request);

        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertThat(result.get("response_content")).isEqualTo("downstream result");
    }

    @Test
    void forwardsAgentIdAndIntentIdFromJsonStringAnswerEnvelope() {
        Map<String, Object> handlerResult = new LinkedHashMap<>();
        handlerResult.put("role", "assistant");
        handlerResult.put("response_content", "酒店预订");
        handlerResult.put("intent_id", "intent_L1_hotel");
        handlerResult.put("agent_id", "agent_card_L2_hotel");
        when(agentHandler.query(any())).thenReturn(
                new QueryResponse(handlerResult, "c-1"));

        // Default RemoteAgentCaller path: final answer emitted as a JSON-string
        // envelope (not a Map). The sync forward path must still propagate
        // agent_id/intent_id from the envelope to the client response.
        // Envelope shape mirrors VersatileResponseExtractor.buildThreeFieldEnvelope:
        // agent_id/intent_id at top level, business text in output/response_content.
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK,
                    "{\"type\":\"answer\",\"output\":\"downstream result\","
                            + "\"response_content\":\"downstream result\","
                            + "\"agent_id\":\"L2-agent\",\"intent_id\":\"L2-intent\"}"));
            obs.onComplete();
            return null;
        }).when(caller).call(any(RemoteAgentCall.class), any(QueryStreamObserver.class));

        A2AEnabledServeOrchestrator orchestrator = new A2AEnabledServeOrchestrator(
                agentHandler, mock(TaskStore.class), caller, resolver, streamRegistry, "agent-L1");

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setStream(false);
        request.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));

        QueryResponse response = orchestrator.query(request);

        Map<?, ?> result = (Map<?, ?>) response.getResult();
        assertThat(result.get("response_content")).isEqualTo("downstream result");
        assertThat(result.get("agent_id")).isEqualTo("L2-agent");
        assertThat(result.get("intent_id")).isEqualTo("L2-intent");
    }

    @Test
    void doesNotForwardWhenResultHasNoAgentId() {
        Map<String, Object> handlerResult = new LinkedHashMap<>();
        handlerResult.put("role", "assistant");
        handlerResult.put("content", "普通回答");
        when(agentHandler.query(any())).thenReturn(
                new QueryResponse(handlerResult, "c-1"));

        A2AEnabledServeOrchestrator orchestrator = new A2AEnabledServeOrchestrator(
                agentHandler, mock(TaskStore.class), caller, resolver, streamRegistry, "agent-L1");

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setStream(false);
        request.setMessages(List.of(Map.of("role", "user", "content", "hi")));

        QueryResponse response = orchestrator.query(request);

        verify(caller, never())
                .call(any(), any());
        assertThat(((Map<?, ?>) response.getResult()).get("content")).isEqualTo("普通回答");
    }

    @Test
    void forwardsEvenWhenAgentIdEqualsOwnAgentId() {
        Map<String, Object> handlerResult = new LinkedHashMap<>();
        handlerResult.put("role", "assistant");
        handlerResult.put("response_content", "reclassify context");
        handlerResult.put("intent_id", "intent_L1");
        handlerResult.put("agent_id", "agent-L1");
        when(agentHandler.query(any())).thenReturn(
                new QueryResponse(handlerResult, "c-1"));

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of(
                    "type", "answer", "output", "reclassified result")));
            obs.onComplete();
            return null;
        }).when(caller).call(any(), any());

        A2AEnabledServeOrchestrator orchestrator = new A2AEnabledServeOrchestrator(
                agentHandler, mock(TaskStore.class), caller, resolver, streamRegistry, "agent-L1");

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setStream(false);
        request.setMessages(List.of(Map.of("role", "user", "content", "hi")));

        orchestrator.query(request);

        ArgumentCaptor<RemoteAgentCall> callCaptor = ArgumentCaptor.forClass(RemoteAgentCall.class);
        verify(caller).call(callCaptor.capture(), any());
        assertThat(callCaptor.getValue().agentId()).isEqualTo("agent-L1");
        assertThat(callCaptor.getValue().responseContent()).isEqualTo("reclassify context");
    }

    @Test
    void forwardsChunksToObserverInStreamingMode() {
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of(
                    "type", "answer",
                    "output", "一层输出",
                    "response_content", "一层输出",
                    "intent_id", "intent_L1_hotel",
                    "agent_id", "agent_card_L2_hotel")));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, "二层中间结果"));
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of(
                    "type", "answer", "output", "二层最终")));
            obs.onComplete();
            return null;
        }).when(caller).call(any(), any());

        A2AEnabledServeOrchestrator orchestrator = new A2AEnabledServeOrchestrator(
                agentHandler, mock(TaskStore.class), caller, resolver, streamRegistry, "agent-L1");

        List<QueryChunk> sink = new ArrayList<>();
        QueryStreamObserver clientObserver = new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                sink.add(chunk);
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
        };

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setStream(true);
        request.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));

        orchestrator.streamQuery(request, clientObserver);

        assertThat(sink).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_CHUNK);
        assertThat(sink).hasSize(2);
    }

    @Test
    void streamingThreeFieldForwardSurfacesRemoteInputRequiredAndTerminates() {
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of(
                    "type", "answer",
                    "output", "一层输出",
                    "response_content", "一层输出",
                    "intent_id", "intent_L1_hotel",
                    "agent_id", "agent_card_L2_hotel")));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_INTERRUPT,
                    Map.of("message", "请补充入住日期", "remote_task_id", "rt-L2-1")));
            obs.onComplete();
            return null;
        }).when(caller).call(any(), any());

        A2AEnabledServeOrchestrator orchestrator = new A2AEnabledServeOrchestrator(
                agentHandler, mock(TaskStore.class), caller, resolver, streamRegistry, "agent-L1");

        List<QueryChunk> sink = new ArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        QueryStreamObserver clientObserver = new QueryStreamObserver() {
            @Override public void onNext(QueryChunk chunk) { sink.add(chunk); }
            @Override public void onComplete() { completed.set(true); }
            @Override public void onError(Throwable e) { }
            @Override public boolean isCancelled() { return false; }
        };

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setStream(true);
        request.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));

        orchestrator.streamQuery(request, clientObserver);

        // The remote INPUT_REQUIRED must reach the client as an interrupt chunk,
        // and the stream MUST terminate (onComplete) — otherwise the client hangs.
        assertThat(sink).extracting(QueryChunk::getType)
                .contains(QueryChunk.TYPE_INTERRUPT);
        assertThat(sink).filteredOn(c -> QueryChunk.TYPE_INTERRUPT.equals(c.getType()))
                .singleElement()
                .satisfies(c -> {
                    Map<?, ?> data = (Map<?, ?>) c.getData();
                    assertThat(data.get("message")).isEqualTo("请补充入住日期");
                    assertThat(data.get("remote_task_id")).isEqualTo("rt-L2-1");
                });
        assertThat(completed).isTrue();
    }

    @Test
    void streamingThreeFieldForwardTerminatesOnRemoteError() {
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onNext(new QueryChunk(QueryChunk.TYPE_CHUNK, Map.of(
                    "type", "answer",
                    "output", "一层输出",
                    "response_content", "一层输出",
                    "intent_id", "intent_L1_hotel",
                    "agent_id", "agent_card_L2_hotel")));
            obs.onComplete();
            return null;
        }).when(agentHandler).streamQuery(any(), any());

        // Remote caller signals an error via observer.onError. The wrapper
        // forwards this to the passthrough (client) observer and marks
        // terminallyNotified; the orchestrator must not double-notify and must
        // not leave the stream open.
        java.util.concurrent.atomic.AtomicReference<Throwable> errorRef = new java.util.concurrent.atomic.AtomicReference<>();
        doAnswer(inv -> {
            QueryStreamObserver obs = inv.getArgument(1);
            obs.onError(new RuntimeException("remote boom"));
            return null;
        }).when(caller).call(any(), any());

        A2AEnabledServeOrchestrator orchestrator = new A2AEnabledServeOrchestrator(
                agentHandler, mock(TaskStore.class), caller, resolver, streamRegistry, "agent-L1");

        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean(false);
        QueryStreamObserver clientObserver = new QueryStreamObserver() {
            @Override public void onNext(QueryChunk chunk) { }
            @Override public void onComplete() { completed.set(true); }
            @Override public void onError(Throwable e) { errorRef.set(e); }
            @Override public boolean isCancelled() { return false; }
        };

        ServeRequest request = new ServeRequest();
        request.setConversationId("c-1");
        request.setStream(true);
        request.setMessages(List.of(Map.of("role", "user", "content", "订酒店")));

        orchestrator.streamQuery(request, clientObserver);

        assertThat(errorRef.get()).isNotNull();
        // onError already terminated the stream; orchestrator must not also call onComplete.
        assertThat(completed).isFalse();
    }
}
