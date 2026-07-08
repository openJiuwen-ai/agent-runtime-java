/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.controller.a2a;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.ServeOrchestrator;

import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.events.EventQueue;
import org.a2aproject.sdk.server.events.EventQueueClosedException;
import org.a2aproject.sdk.server.events.EventQueueItem;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link A2AAgentExecutor}.
 */
class A2AAgentExecutorTest {
    @Test
    void syncCompletedPathWaitsForEnqueuedFinalEventToDrain() {
        ServeOrchestrator orchestrator = mock(ServeOrchestrator.class);
        when(orchestrator.query(any())).thenReturn(new QueryResponse(Map.of("content", "done"), "ctx-1"));
        A2AProtocolAdapter adapter = mock(A2AProtocolAdapter.class);
        ServeRequest request = new ServeRequest();
        request.setConversationId("ctx-1");
        request.setStream(false);
        when(adapter.toServeRequest(any())).thenReturn(request);

        A2AAgentExecutor executor = new A2AAgentExecutor(orchestrator, adapter);
        CountingEventQueue queue = new CountingEventQueue();
        RequestContext context = requestContext("task-1", "ctx-1", false);
        AgentEmitter emitter = new AgentEmitter(context, queue);

        executor.execute(context, emitter);

        assertThat(queue.sizeCalls.get()).isPositive();
    }

    private static RequestContext requestContext(String taskId, String contextId, boolean isStream) {
        RequestContext context = mock(RequestContext.class);
        when(context.getTaskId()).thenReturn(taskId);
        when(context.getContextId()).thenReturn(contextId);
        when(context.getMetadata()).thenReturn(Map.of());
        ServerCallContext callContext = mock(ServerCallContext.class);
        when(callContext.getState()).thenReturn(new java.util.HashMap<>(Map.of("_a2a_stream", isStream)));
        when(context.getCallContext()).thenReturn(callContext);
        return context;
    }

    private static final class CountingEventQueue extends EventQueue {
        private final AtomicInteger sizeCalls = new AtomicInteger();

        @Override
        public void awaitQueuePollerStart() {
        }

        @Override
        public void signalQueuePollerStarted() {
        }

        @Override
        public void enqueueItem(EventQueueItem item) {
        }

        @Override
        public EventQueue tap() {
            throw new UnsupportedOperationException("not needed");
        }

        @Override
        public EventQueueItem dequeueEventItem(int waitMilliSeconds) throws EventQueueClosedException {
            return null;
        }

        @Override
        public int size() {
            sizeCalls.incrementAndGet();
            return 0;
        }

        @Override
        public void close() {
        }

        @Override
        public void close(boolean isImmediate) {
        }

        @Override
        public void close(boolean isImmediate, boolean shouldNotifyParent) {
        }
    }
}
