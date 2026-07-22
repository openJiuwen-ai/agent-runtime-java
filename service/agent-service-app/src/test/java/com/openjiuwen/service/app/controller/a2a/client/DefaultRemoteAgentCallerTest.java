/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a.client;

import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultRemoteAgentCallerTest {
    private A2ARemoteAgentCardRegistry registry;
    private DefaultRemoteAgentCaller caller;

    @BeforeEach
    void setUp() {
        registry = mock(A2ARemoteAgentCardRegistry.class);
        caller = new DefaultRemoteAgentCaller(registry);
    }

    @Test
    void supportedReturnsTrueWhenRegistryHasAgent() {
        when(registry.get("agent-a")).thenReturn(java.util.Optional.of(
                new A2ARemoteAgentCardRegistry.RemoteAgentEntry("agent-a", null, 30)));
        assertThat(caller.supported("agent-a")).isTrue();
    }

    @Test
    void supportedReturnsFalseWhenRegistryMisses() {
        when(registry.get("agent-x")).thenReturn(java.util.Optional.empty());
        assertThat(caller.supported("agent-x")).isFalse();
    }

    @Test
    void callRoutesUnknownAgentToObserverOnError() {
        when(registry.get("agent-x")).thenReturn(java.util.Optional.empty());
        java.util.concurrent.atomic.AtomicReference<Throwable> errorRef = new java.util.concurrent.atomic.AtomicReference<>();
        QueryStreamObserver observer = new QueryStreamObserver() {
            @Override public void onNext(QueryChunk chunk) { }
            @Override public void onComplete() { }
            @Override public void onError(Throwable e) { errorRef.set(e); }
            @Override public boolean isCancelled() { return false; }
        };

        caller.call(new RemoteAgentCall("agent-x", new ServeRequest()), observer);

        assertThat(errorRef.get()).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unknown remote agent");
    }

    /**
     * Verifies that {@link DefaultRemoteAgentCaller} does not mutate the serve
     * request's {@code messages} when the agent is unknown (early onError path).
     *
     * <p>The broader contract that {@code responseContent} is ignored is
     * documented on {@link RemoteAgentCall} and exercised by integration tests;
     * this unit test only covers the no-mutation guarantee on the unknown-agent
     * path.
     */
    @Test
    void doesNotMutateRequestMessagesWhenAgentUnknown() {
        when(registry.get("agent-a")).thenReturn(java.util.Optional.empty());
        ServeRequest request = new ServeRequest();
        request.setMessages(List.of(java.util.Map.of("role", "user", "content", "hi")));
        int messagesBefore = request.getMessages().size();

        caller.call(new RemoteAgentCall("agent-a", request, "upstream-content", null, null),
                capturingObserver(new ArrayList<>()));

        assertThat(request.getMessages()).hasSize(messagesBefore);
    }

    private static QueryStreamObserver capturingObserver(List<QueryChunk> sink) {
        return new QueryStreamObserver() {
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
    }
}
