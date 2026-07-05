/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demol1test.query;

import com.openjiuwen.service.app.controller.query.QueryMvcController;
import com.openjiuwen.service.app.lifecycle.AgentLifecycleManager;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Internal test helper: scenario-switchable Query REST L1 validation app.
 *
 * @since 2026-07-03
 */
@SpringBootApplication
public class QueryL1RestExample {
    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(QueryL1RestExample.class);
        application.setDefaultProperties(
            Map.of("server.port", "8090", "spring.main.web-application-type", "servlet", "spring.application.name",
                "query-l1-example", "openjiuwen.service.version", "0.1.0", "example.query.l1.handler", "echo",
                "example.query.l1.stream-chunks", "1", "example.query.l1.stream-delay-ms", "0"));
        application.run(args);
    }

    @Bean
    @ConditionalOnProperty(prefix = "example.query.l1", name = "lifecycle", havingValue = "disabled")
    AgentLifecycleManager disabledQueryL1LifecycleManager() {
        return new AgentLifecycleManager() {
            @Override
            public void runInitPhase() {
                // Keep readiness in its pre-init state for agent-not-loaded scenarios.
            }

            @Override
            public void runShutdownPhase() {
                // No-op: this example does not start external resources.
            }

            @Override
            public void interrupt(String conversationId) {
                // No active work is started outside request handling.
            }
        };
    }

    @Configuration
    @ConditionalOnProperty(prefix = "example.query.l1", name = "controller", havingValue = "query-only")
    @Import(QueryMvcController.class)
    static class QueryOnlyControllerConfiguration {}

    @Bean
    @ConditionalOnProperty(prefix = "example.query.l1", name = "handler", havingValue = "echo", matchIfMissing = true)
    AgentHandler queryL1EchoAgentHandler(@Value("${example.query.l1.stream-chunks:1}") int streamChunks,
        @Value("${example.query.l1.stream-delay-ms:0}") long streamDelayMs) {
        return new EchoAgentHandler(streamChunks, streamDelayMs);
    }

    @Bean
    @ConditionalOnProperty(prefix = "example.query.l1", name = "handler", havingValue = "failing-start")
    AgentHandler failingStartQueryL1AgentHandler() {
        return new AgentHandler() {
            @Override
            public void start() {
                throw new IllegalStateException("query l1 forced start failure");
            }

            @Override
            public QueryResponse query(ServeRequest request) {
                throw new AssertionError("query should not be called when agent is not loaded");
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
                throw new AssertionError("streamQuery should not be called when agent is not loaded");
            }
        };
    }

    private static final class EchoAgentHandler implements AgentHandler {
        private final int streamChunks;

        private final long streamDelayMs;

        private final Map<String, List<String>> conversationHistory = new ConcurrentHashMap<>();

        private EchoAgentHandler(int streamChunks, long streamDelayMs) {
            this.streamChunks = Math.max(1, streamChunks);
            this.streamDelayMs = Math.max(0, streamDelayMs);
        }

        @Override
        public QueryResponse query(ServeRequest request) {
            return new QueryResponse(responseBody(request, 1), request.getConversationId());
        }

        @Override
        public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            for (int i = 1; i <= streamChunks; i++) {
                if (observer.isCancelled()) {
                    return;
                }
                observer.onNext(new QueryChunk("chunk", responseBody(request, i)));
                if (streamDelayMs > 0 && i < streamChunks) {
                    sleep(streamDelayMs);
                }
            }
            observer.onComplete();
        }

        private Map<String, Object> responseBody(ServeRequest request, int chunkIndex) {
            String query = request.lastUserQuery();
            List<String> history = conversationHistory.computeIfAbsent(request.getConversationId(),
                ignored -> new ArrayList<>());
            String previousQuery = history.isEmpty() ? null : history.get(history.size() - 1);
            history.add(query);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("role", "assistant");
            body.put("content", "query-l1:" + query);
            body.put("query", query);
            body.put("conversation_id", request.getConversationId());
            body.put("user_id", request.getUserId());
            body.put("space_id", request.getSpaceId());
            body.put("tenant_id", request.getTenantId());
            body.put("messages_size", request.getMessages().size());
            body.put("stream", request.isStream());
            body.put("turn", history.size());
            body.put("previous_query", previousQuery);
            body.put("chunk_index", chunkIndex);
            return body;
        }

        private static void sleep(long delayMs) {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ex) {
                // donothing
            }
        }
    }
}
