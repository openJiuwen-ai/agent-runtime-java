package com.openjiuwen.service.adapters.agentfw;

import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CoreAgentHandlerTest {

    @Test
    @SuppressWarnings("unchecked")
    void passesQueryContextToRunnerInputs() {
        CapturingAgent agent = new CapturingAgent();
        CoreAgentHandler handler = new CoreAgentHandler(agent);

        ServeRequest request = request("c-inputs", "hello");
        request.setUserId("u1");
        request.setSpaceId("s1");
        request.setTenantId("t1");

        handler.query(request);

        Map<String, Object> inputs = (Map<String, Object>) agent.lastInputs;
        assertThat(inputs).containsEntry("conversation_id", "c-inputs");
        assertThat(inputs).containsEntry("query", "hello");
        assertThat(inputs).containsEntry("user_id", "u1");
        assertThat(inputs).containsEntry("space_id", "s1");
        assertThat(inputs).containsEntry("tenant_id", "t1");
        assertThat((List<Map<String, Object>>) inputs.get("messages")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void realRunnerSessionRetainsContextForSameConversation() {
        CoreAgentHandler handler = new CoreAgentHandler(new SessionEchoAgent());

        QueryResponse first = handler.query(request("c-core-session", "a"));
        QueryResponse second = handler.query(request("c-core-session", "b"));

        assertThat((Map<String, Object>) first.getResult()).containsEntry("content", "turn1:a");
        assertThat((Map<String, Object>) second.getResult()).containsEntry("content", "turn2:b|prev=a");
    }

    @Test
    void observerCancellationStopsRunnerIteration() {
        CountingAgent agent = new CountingAgent();
        CoreAgentHandler handler = new CoreAgentHandler(agent);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        List<QueryChunk> chunks = new ArrayList<>();

        handler.streamQuery(request("c-cancel", "stop"), new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk);
                cancelled.set(true);
            }

            @Override
            public void onError(Throwable error) {
            }

            @Override
            public void onComplete() {
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        });

        assertThat(chunks).hasSize(1);
        assertThat(agent.nextCount.get()).isEqualTo(1);
    }

    private static ServeRequest request(String conversationId, String content) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        request.setMessages(List.of(Map.of("role", "user", "content", content)));
        request.setUserId("anonymous");
        request.setSpaceId("default");
        return request;
    }

    @Test
    void startStartsRunnerAndStopStopsIt() {
        CoreAgentHandler handler = new CoreAgentHandler("agent-id");

        handler.start();
        assertThat(CoreAgentHandler.isRunnerStarted()).isTrue();

        handler.stop();
        assertThat(CoreAgentHandler.isRunnerStarted()).isFalse();
    }

    @Test
    void startIsIdempotent() {
        CoreAgentHandler handler = new CoreAgentHandler("agent-id");

        handler.start();
        handler.start();

        assertThat(CoreAgentHandler.isRunnerStarted()).isTrue();
        handler.stop();
    }

    public static class CapturingAgent {
        private Object lastInputs;

        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            this.lastInputs = inputs;
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "ok"))).iterator();
        }
    }

    public static class SessionEchoAgent {
        @SuppressWarnings("unchecked")
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            Map<String, Object> inputMap = (Map<String, Object>) inputs;
            String query = String.valueOf(inputMap.get("query"));
            Object priorState = session.getState("history");
            List<String> history = priorState instanceof List<?>
                    ? new ArrayList<>((List<String>) priorState)
                    : new ArrayList<>();
            String reply = "turn" + (history.size() + 1) + ":" + query;
            if (!history.isEmpty()) {
                reply += "|prev=" + String.join(",", history);
            }
            history.add(query);
            session.updateState(Map.of("history", history));
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", reply))).iterator();
        }
    }

    public static class CountingAgent {
        private final AtomicInteger nextCount = new AtomicInteger();

        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return new Iterator<>() {
                @Override
                public boolean hasNext() {
                    return nextCount.get() < 5;
                }

                @Override
                public Object next() {
                    int index = nextCount.incrementAndGet();
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("content", "chunk" + index);
                    return new OutputSchema("llm_output", index, payload);
                }
            };
        }
    }
}
