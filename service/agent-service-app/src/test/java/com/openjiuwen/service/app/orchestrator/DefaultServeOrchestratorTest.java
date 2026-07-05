/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

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
}
