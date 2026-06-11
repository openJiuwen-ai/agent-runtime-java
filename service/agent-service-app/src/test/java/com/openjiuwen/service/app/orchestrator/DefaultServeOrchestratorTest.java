package com.openjiuwen.service.app.orchestrator;

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
import static org.assertj.core.api.Assertions.assertThat;

class DefaultServeOrchestratorTest {

    private final ActiveStreamRegistry streamRegistry = new ActiveStreamRegistry();

    @Test
    void queryDelegatesToHandler() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("c1");
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
        assertThat(orchestrator.query(request)).isEqualTo(expected);
    }

    @Test
    void streamQueryDelegatesToHandler() {
        ServeRequest request = new ServeRequest();
        request.setConversationId("c2");
        List<QueryChunk> chunks = new ArrayList<>();

        AgentHandler handler = new AgentHandler() {
            @Override
            public QueryResponse query(ServeRequest req) {
                return null;
            }

            @Override
            public void streamQuery(ServeRequest req, QueryStreamObserver observer) {
                observer.onNext(new QueryChunk("chunk", Map.of("content", "ok")));
                observer.onComplete();
            }
        };

        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, streamRegistry);
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
        ServeRequest request = new ServeRequest();
        request.setConversationId("c-error");
        List<QueryChunk> chunks = new ArrayList<>();

        AgentHandler handler = new AgentHandler() {
            @Override
            public QueryResponse query(ServeRequest req) {
                return null;
            }

            @Override
            public void streamQuery(ServeRequest req, QueryStreamObserver observer) {
                throw new RuntimeException("boom");
            }
        };

        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, streamRegistry);
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
        assertThat((Map<String, Object>) chunks.get(0).getData()).containsEntry("type", "error");
        assertThat((Map<String, Object>) chunks.get(0).getData()).containsEntry("error", "boom");
    }
}
