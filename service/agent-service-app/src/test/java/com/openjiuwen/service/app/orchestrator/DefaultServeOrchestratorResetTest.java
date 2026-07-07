/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.lifecycle.ActiveStreamRegistry;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.AgentHandler;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DefaultServeOrchestratorResetTest
 *
 * @since 2026-07-03
 */
class DefaultServeOrchestratorResetTest {
    @Test
    void resetConversationCancelsActiveStreamAndClearsSession() {
        AtomicBoolean cleared = new AtomicBoolean(false);
        AgentHandler handler = new AgentHandler() {
            @Override
            public void clearSession(String conversationId) {
                cleared.set(true);
                assertThat(conversationId).isEqualTo("reset-me");
            }

            @Override
            public QueryResponse query(ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            }
        };
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, registry);
        var handle = registry.register("reset-me");

        orchestrator.resetConversation("reset-me");

        assertThat(handle.isCancelled()).isTrue();
        assertThat(cleared.get()).isTrue();
        assertThat(registry.activeCount()).isZero();
    }

    @Test
    void resetConversationCancelsBeforeClearSession() {
        java.util.List<String> order = new java.util.ArrayList<>();
        AgentHandler handler = new AgentHandler() {
            @Override
            public void clearSession(String conversationId) {
                order.add("clear");
            }

            @Override
            public QueryResponse query(ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            }
        };
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, registry);
        var handle = registry.register("order-me");

        orchestrator.resetConversation("order-me");

        assertThat(handle.isCancelled()).isTrue();
        assertThat(order).containsExactly("clear");
    }

    @Test
    void resetConversationSkipsBlankConversationId() {
        AtomicBoolean cleared = new AtomicBoolean(false);
        AgentHandler handler = new AgentHandler() {
            @Override
            public void clearSession(String conversationId) {
                cleared.set(true);
            }

            @Override
            public QueryResponse query(ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            }
        };
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, registry);

        orchestrator.resetConversation("");
        orchestrator.resetConversation(null);

        assertThat(cleared.get()).isFalse();
    }

    @Test
    void resetConversationClearsSessionWithoutActiveStream() {
        AtomicBoolean cleared = new AtomicBoolean(false);
        AgentHandler handler = new AgentHandler() {
            @Override
            public void clearSession(String conversationId) {
                cleared.set(true);
                assertThat(conversationId).isEqualTo("idle-me");
            }

            @Override
            public QueryResponse query(ServeRequest request) {
                return null;
            }

            @Override
            public void streamQuery(ServeRequest request, QueryStreamObserver observer) {
            }
        };
        ActiveStreamRegistry registry = new ActiveStreamRegistry();
        DefaultServeOrchestrator orchestrator = new DefaultServeOrchestrator(handler, registry);

        orchestrator.resetConversation("idle-me");

        assertThat(cleared.get()).isTrue();
        assertThat(registry.activeCount()).isZero();
    }
}
