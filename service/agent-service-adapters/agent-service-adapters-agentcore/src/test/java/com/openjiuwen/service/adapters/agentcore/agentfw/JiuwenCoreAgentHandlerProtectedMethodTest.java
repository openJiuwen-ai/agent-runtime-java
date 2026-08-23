/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Verifies that the extracted {@code executeAgentStreaming} / {@code executeAgent}
 * protected methods behave as extension points without breaking existing behavior.
 *
 * @since 0.1.2
 */
class JiuwenCoreAgentHandlerProtectedMethodTest {
    @Test
    void executeAgentStreaming_usesSingleAgent_byDefault() {
        JiuwenCoreAgentHandlerTest.CapturingAgent agent = new JiuwenCoreAgentHandlerTest.CapturingAgent();
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agent);
        ServeRequest request = request("c-default-stream", "hello");

        Iterator<Object> result = handler.executeAgentStreaming(
                JiuwenCoreAgentHandler.buildInputs(request),
                handler.runnerSession(request),
                List.of(StreamMode.OUTPUT));

        assertThat(result).isNotNull();
        assertThat(result.hasNext()).isTrue();
        result.next();
        assertThat(result.hasNext()).isFalse();
    }

    @Test
    void executeAgent_canBeOverridden() {
        TrackingInvokeAgent originalAgent = new TrackingInvokeAgent();
        TrackingInvokeAgent replacementAgent = new TrackingInvokeAgent();

        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(originalAgent) {
            @Override
            protected Object executeAgent(Map<String, Object> inputs, Object session) {
                return Runner.runAgent(replacementAgent, inputs, session, null);
            }
        };

        handler.query(request("c-override-invoke", "hello"));

        assertThat(replacementAgent.invokeCount.get()).isEqualTo(1);
        assertThat(originalAgent.invokeCount.get()).isZero();
    }

    @Test
    void queryViaStreaming_usesExecuteAgentStreaming() {
        AtomicBoolean streamingCalled = new AtomicBoolean(false);
        JiuwenCoreAgentHandlerTest.CapturingAgent agent = new JiuwenCoreAgentHandlerTest.CapturingAgent();

        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agent) {
            @Override
            protected Iterator<Object> executeAgentStreaming(Map<String, Object> inputs, Object session,
                    List<StreamMode> streamModes) {
                streamingCalled.set(true);
                return super.executeAgentStreaming(inputs, session, streamModes);
            }
        };

        handler.query(request("c-fallback-stream", "hello"));

        assertThat(streamingCalled.get()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamQuery_chunkNormalization_preserved() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new JiuwenCoreAgentHandlerTest.CountingAgent());
        List<QueryChunk> chunks = new ArrayList<>();

        handler.streamQuery(request("c-norm", "run"), collectingObserver(chunks));

        assertThat(chunks).hasSize(5);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_CHUNK));
        Map<String, Object> firstData = (Map<String, Object>) chunks.get(0).getData();
        assertThat(firstData).containsEntry("type", "llm_output");
    }

    private static ServeRequest request(String conversationId, String content) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        request.setMessages(List.of(Map.of("role", "user", "content", content)));
        request.setUserId("anonymous");
        request.setSpaceId("default");
        return request;
    }

    private static QueryStreamObserver collectingObserver(List<QueryChunk> chunks) {
        return new QueryStreamObserver() {
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
        };
    }

    /** Test agent that tracks invoke calls for override verification. */
    public static class TrackingInvokeAgent {
        final AtomicInteger invokeCount = new AtomicInteger();

        /**
         * Invokes the agent and tracks the call count.
         *
         * @param inputs agent inputs
         * @param session agent session
         * @return a map containing the reply output
         */
        @SuppressWarnings("unchecked")
        public Object invoke(Object inputs, Session session) {
            invokeCount.incrementAndGet();
            Map<String, Object> inputMap = (Map<String, Object>) inputs;
            String query = String.valueOf(inputMap.get("query"));
            return Map.of("output", "replied:" + query, "result_type", "answer");
        }

        /**
         * Returns a single fallback output schema for streaming.
         *
         * @param inputs agent inputs
         * @param session agent session
         * @param streamModes stream modes
         * @return an iterator containing a single fallback output schema
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "fallback"))).iterator();
        }
    }
}
