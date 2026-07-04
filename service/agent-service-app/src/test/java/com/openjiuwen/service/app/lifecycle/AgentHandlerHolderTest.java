/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AgentHandlerHolderTest
 *
 * @since 2026-07-03
 */
class AgentHandlerHolderTest {
    @Test
    void rejectsQueryBeforeAgentLoaded() {
        AgentHandlerHolder holder = new AgentHandlerHolder();

        assertThat(holder.isLoaded()).isFalse();
        assertThatThrownBy(() -> holder.query(new ServeRequest())).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Agent not loaded");
    }

    @Test
    void rejectsStreamQueryBeforeAgentLoaded() {
        AgentHandlerHolder holder = new AgentHandlerHolder();

        assertThatThrownBy(() -> holder.streamQuery(new ServeRequest(), noopObserver())).isInstanceOf(
            IllegalStateException.class).hasMessageContaining("Agent not loaded");
    }

    @Test
    void delegatesAfterHandlerSet() {
        AgentHandlerHolder holder = new AgentHandlerHolder();
        holder.setHandler(new DemoAgentHandler());

        assertThat(holder.isLoaded()).isTrue();
        assertThat(holder.query(request("hello")).getResult()).isEqualTo("demo:hello");
    }

    @Test
    void delegatesClearSessionAfterHandlerSet() {
        AtomicBoolean cleared = new AtomicBoolean(false);
        AgentHandlerHolder holder = new AgentHandlerHolder();
        holder.setHandler(new DemoAgentHandler(cleared));

        holder.clearSession("c1");

        assertThat(cleared.get()).isTrue();
    }

    @Test
    void clearSessionNoOpBeforeAgentLoaded() {
        AgentHandlerHolder holder = new AgentHandlerHolder();

        holder.clearSession("c1");

        assertThat(holder.isLoaded()).isFalse();
    }

    private static QueryStreamObserver noopObserver() {
        return new QueryStreamObserver() {
            @Override
            public void onNext(com.openjiuwen.service.spec.dto.QueryChunk chunk) {
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
            }
        };
    }

    private static ServeRequest request(String message) {
        ServeRequest request = new ServeRequest();
        request.setConversationId("c1");
        request.getMessages().add(Map.of("role", "user", "content", message));
        return request;
    }

    private static final class DemoAgentHandler implements com.openjiuwen.service.spec.spi.AgentHandler {
        private final AtomicBoolean cleared;

        private DemoAgentHandler() {
            this.cleared = null;
        }

        private DemoAgentHandler(AtomicBoolean cleared) {
            this.cleared = cleared;
        }

        @Override
        public com.openjiuwen.service.spec.dto.QueryResponse query(
            com.openjiuwen.service.spec.dto.ServeRequest request) {
            return new com.openjiuwen.service.spec.dto.QueryResponse("demo:" + request.lastUserQuery(),
                request.getConversationId());
        }

        @Override
        public void streamQuery(com.openjiuwen.service.spec.dto.ServeRequest request, QueryStreamObserver observer) {
        }

        @Override
        public void clearSession(String conversationId) {
            if (cleared != null) {
                cleared.set(true);
            }
        }
    }
}
