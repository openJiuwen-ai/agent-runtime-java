/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DefaultServeOrchestratorTest
 *
 * @since 2026-07-03
 */
class DefaultServeOrchestratorTest {
    private final ActiveStreamRegistry streamRegistry = new ActiveStreamRegistry();

    @Test
    void queryDelegatesToHandler() {
        QueryResponse expected = new QueryResponse(Map.of("content", "ok"), "c1");
        AgentHandler handler = new AgentHandler() {
            @Override
            public QueryResponse query(ServeRequest req) {
                assertThat(req.getConversationId()).isEqualTo("c1");
                return expected;
            }

            @Override
            public void streamQuery(ServeRequest req, QueryStreamObserver observer) {
            }
        };
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, streamRegistry);
        ServeRequest request = new ServeRequest();
        request.setConversationId("c1");
        assertThat(orchestrator.query(request)).isEqualTo(expected);
    }

    @Test
    void streamQueryDelegatesToHandler() {
        AgentHandler handler = new AgentHandler() {
            @Override
            public void streamQuery(ServeRequest req, QueryStreamObserver observer) {
                observer.onNext(new QueryChunk("chunk", Map.of("content", "ok")));
                observer.onComplete();
            }

            @Override
            public QueryResponse query(ServeRequest req) {
                return null;
            }
        };
        List<QueryChunk> chunks = new ArrayList<>();
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, streamRegistry);
        ServeRequest request = new ServeRequest();
        request.setConversationId("c2");
        orchestrator.streamQuery(request, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
            }
        });

        assertThat(chunks).hasSize(1);
        assertThat((Map<String, Object>) chunks.get(0).getData()).containsEntry("content", "ok");
    }

    @Test
    void streamQuerySurfacesHandlerExceptionAsErrorEvent() {
        AgentHandler handler = new AgentHandler() {
            @Override
            public void streamQuery(ServeRequest req, QueryStreamObserver observer) {
                throw new RuntimeException("boom");
            }

            @Override
            public QueryResponse query(ServeRequest req) {
                return null;
            }
        };
        List<QueryChunk> chunks = new ArrayList<>();
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, streamRegistry);
        ServeRequest request = new ServeRequest();
        AtomicReference<Throwable> streamError = new AtomicReference<>();
        request.setConversationId("c-error");
        AtomicBoolean completed = new AtomicBoolean();
        orchestrator.streamQuery(request, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onError(Throwable error) {
                streamError.set(error);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getType()).isEqualTo("error");
        assertThat((Map<String, Object>) chunks.get(0).getData()).containsEntry("type", "error");
        assertThat((Map<String, Object>) chunks.get(0).getData()).containsEntry("error", "boom");
        assertThat(streamError.get()).isInstanceOf(RuntimeException.class).hasMessage("boom");
        assertThat(completed.get()).isFalse();
    }

    @Test
    void queryRoundTripsTaskLifecycleHooks() {
        Object token = new Object();
        List<String> events = new ArrayList<>();
        AgentHandler handler = new AgentHandler() {
            @Override
            public Optional<Object> prepareTask(ServeRequest req) {
                events.add("prepare");
                return Optional.of(token);
            }

            @Override
            public void completeTask(Optional<Object> taskToken) {
                events.add("complete:" + (taskToken.orElse(null) == token));
            }

            @Override
            public QueryResponse query(ServeRequest req) {
                events.add("query");
                return new QueryResponse(Map.of("content", "ok"), req.getConversationId());
            }

            @Override
            public void streamQuery(ServeRequest req, QueryStreamObserver observer) {
            }
        };
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, streamRegistry);
        ServeRequest request = new ServeRequest();
        request.setConversationId("c-hooks");

        assertThat(orchestrator.query(request)).isNotNull();
        assertThat(events).containsExactly("prepare", "query", "complete:true");
    }

    @Test
    void streamQueryRoundTripsTaskLifecycleHooks() {
        Object token = new Object();
        List<String> events = new ArrayList<>();
        AgentHandler handler = new AgentHandler() {
            @Override
            public Optional<Object> prepareTask(ServeRequest req) {
                events.add("prepare");
                return Optional.of(token);
            }

            @Override
            public void completeTask(Optional<Object> taskToken) {
                events.add("complete:" + (taskToken.orElse(null) == token));
            }

            @Override
            public QueryResponse query(ServeRequest req) {
                return null;
            }

            @Override
            public void streamQuery(ServeRequest req, QueryStreamObserver observer) {
                events.add("streamQuery");
                observer.onComplete();
            }
        };
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, streamRegistry);
        ServeRequest request = new ServeRequest();
        request.setConversationId("c-stream-hooks");

        orchestrator.streamQuery(request, new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
            }
        });
        assertThat(events).containsExactly("prepare", "streamQuery", "complete:true");
    }

    @Test
    void prepareTaskRejectionStillCallsCompleteTaskWithEmptyToken() {
        List<String> events = new ArrayList<>();
        AgentHandler handler = new AgentHandler() {
            @Override
            public Optional<Object> prepareTask(ServeRequest req) {
                events.add("prepare");
                throw new IllegalStateException("conversation busy");
            }

            @Override
            public void completeTask(Optional<Object> taskToken) {
                events.add("complete:" + taskToken);
            }

            @Override
            public QueryResponse query(ServeRequest req) {
                events.add("query");
                return null;
            }

            @Override
            public void streamQuery(ServeRequest req, QueryStreamObserver observer) {
            }
        };
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, streamRegistry);
        ServeRequest request = new ServeRequest();
        request.setConversationId("c-busy");

        assertThatThrownBy(() -> orchestrator.query(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("conversation busy");
        assertThat(events).containsExactly("prepare", "complete:Optional.empty");
    }
}
