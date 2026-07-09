/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.common.schema.BaseCard;
import com.openjiuwen.core.memory.external.MemoryProvider;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.ExternalMemoryRail;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
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

/**
 * JiuwenCoreAgentHandlerTest
 *
 * @since 2026-07-03
 */
class JiuwenCoreAgentHandlerTest {
    @Test
    @SuppressWarnings("unchecked")
    void passesQueryContextToRunnerInputs() {
        CapturingAgent agent = new CapturingAgent();
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agent);

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
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new SessionEchoAgent());

        QueryResponse first = handler.query(request("c-core-session", "a"));
        QueryResponse second = handler.query(request("c-core-session", "b"));

        assertThat((Map<String, Object>) first.getResult()).containsEntry("content", "turn1:a");
        assertThat((Map<String, Object>) second.getResult()).containsEntry("content", "turn2:b|prev=a");
    }

    @Test
    void observerCancellationStopsRunnerIteration() {
        CountingAgent agent = new CountingAgent();
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agent);
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

    @Test
    void queryProvidesStableAgentIdForPlainAgentSession() {
        SessionMetadataAgent agent = new SessionMetadataAgent();
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agent);

        handler.query(request("c-plain-agent", "hello"));

        assertThat(agent.agentId).isEqualTo("service-agentcore:" + SessionMetadataAgent.class.getName());
    }

    @Test
    void stringAgentIdProvidesStableAgentIdForCoreSession() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("it-agent");

        ServeRequest request = request("c-string-agent", "hello");
        request.setUserId("user-42");
        request.setSpaceId("space-42");
        request.setTenantId("tenant-42");

        Object session = handler.runnerSession(request);

        assertThat(session).isInstanceOf(AgentSessionApi.class);
        if (!(session instanceof AgentSessionApi agentSession)) {
            throw new AssertionError("Expected AgentSessionApi session");
        }
        assertThat(agentSession.getAgentId()).isEqualTo("it-agent");
        assertThat(agentSession.getEnv("user_id")).isEqualTo("user-42");
        assertThat(agentSession.getEnv("space_id")).isEqualTo("space-42");
        assertThat(agentSession.getEnv("tenant_id")).isEqualTo("tenant-42");
    }

    @Test
    void cardBackedAgentSessionCarriesRequestScopeEnvs() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new CardBackedAgent());
        ServeRequest request = request("c-card-agent", "hello");
        request.setUserId("card-user");
        request.setSpaceId("card-space");
        request.setTenantId("card-tenant");

        Object session = handler.runnerSession(request);

        assertThat(session).isInstanceOf(AgentSessionApi.class);
        if (!(session instanceof AgentSessionApi agentSession)) {
            throw new AssertionError("Expected AgentSessionApi session");
        }
        assertThat(agentSession.getSessionId()).isEqualTo("c-card-agent");
        assertThat(agentSession.getAgentId()).isEqualTo("card-agent");
        assertThat(agentSession.getEnv("user_id")).isEqualTo("card-user");
        assertThat(agentSession.getEnv("space_id")).isEqualTo("card-space");
        assertThat(agentSession.getEnv("tenant_id")).isEqualTo("card-tenant");
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncQueryUsesInvokePathWhenAgentSupportsIt() {
        InvokeEchoAgent agent = new InvokeEchoAgent();
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agent);

        QueryResponse first = handler.query(request("c-invoke-path", "a"));
        QueryResponse second = handler.query(request("c-invoke-path", "b"));

        assertThat(agent.invokeCount.get()).isEqualTo(2);
        assertThat(agent.streamCount.get()).isZero();
        assertThat((Map<String, Object>) first.getResult()).containsEntry("content", "turn1:a");
        assertThat((Map<String, Object>) second.getResult()).containsEntry("content", "turn2:b|prev=a");
    }

    @Test
    void doesNotAttachMemoryProviderRailToDeepAgent() {
        DeepAgent deepAgent = HarnessFactory.createDeepAgent(
            AgentCard.builder().name("memory-agent").description("memory agent").build(),
            DeepAgentConfig.builder().rails(new ArrayList<>()).build(),
            Workspace.builder().rootPath(".").language("en").build());

        new JiuwenCoreAgentHandler(deepAgent, null, ExternalSvcAdapterRegistrar.noop(), new AvailableMemoryProvider());

        assertThat(deepAgent.getConfig().getRails()).noneMatch(ExternalMemoryRail.class::isInstance);
    }

    private static ServeRequest request(String conversationId, String content) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        request.setMessages(List.of(Map.of("role", "user", "content", content)));
        request.setUserId("anonymous");
        request.setSpaceId("default");
        return request;
    }

    private static final class AvailableMemoryProvider implements MemoryProvider {
        @Override
        public String getName() {
            return "test";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void initialize(Map<String, Object> kwargs) {
        }

        @Override
        public List<Map<String, Object>> getToolSchemas() {
            return List.of();
        }

        @Override
        public String handleToolCall(String toolName, Map<String, Object> args) {
            return "{}";
        }

        @Override
        public String prefetch(String query, Map<String, Object> kwargs) {
            return "";
        }

        @Override
        public void syncTurn(String userMsg, String assistantMsg, Map<String, Object> kwargs) {
        }
    }

    @Test
    void startStartsRunnerAndStopStopsIt() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");

        handler.start();
        assertThat(JiuwenCoreAgentHandler.isRunnerStarted()).isTrue();

        handler.stop();
        assertThat(JiuwenCoreAgentHandler.isRunnerStarted()).isFalse();
    }

    @Test
    void startIsIdempotent() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");

        handler.start();
        handler.start();

        assertThat(JiuwenCoreAgentHandler.isRunnerStarted()).isTrue();
        handler.stop();
    }

    @Test
    void startRegistersExternalServicesOnceBeforeRunnerStarts() {
        RecordingRegistrar registrar = new RecordingRegistrar();
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id", registrar);

        handler.start();
        handler.start();

        assertThat(registrar.registerToCalls).isZero();
        assertThat(registrar.registerToRunnerCalls).isEqualTo(1);
        handler.stop();
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearSessionReleasesRunnerSessionMemory() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new SessionEchoAgent());

        handler.query(request("c-reset-session", "a"));
        QueryResponse second = handler.query(request("c-reset-session", "b"));
        assertThat((Map<String, Object>) second.getResult()).containsEntry("content", "turn2:b|prev=a");

        handler.clearSession("c-reset-session");

        QueryResponse third = handler.query(request("c-reset-session", "c"));
        assertThat((Map<String, Object>) third.getResult()).containsEntry("content", "turn1:c");
    }

    @Test
    @SuppressWarnings("unchecked")
    void clearSessionSkipsBlankId() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new SessionEchoAgent());

        handler.query(request("c-blank-clear", "a"));
        QueryResponse second = handler.query(request("c-blank-clear", "b"));
        assertThat((Map<String, Object>) second.getResult()).containsEntry("content", "turn2:b|prev=a");

        handler.clearSession(null);
        handler.clearSession("");
        handler.clearSession("   ");

        QueryResponse third = handler.query(request("c-blank-clear", "c"));
        assertThat((Map<String, Object>) third.getResult()).containsEntry("content", "turn3:c|prev=a,b");
    }

    /** Test agent that captures the last runner inputs. */
    public static class CapturingAgent {
        private Object lastInputs;

        /**
         * Streams a single output chunk and records the inputs.
         *
         * @param inputs inputs
         * @param session session
         * @param streamModes streamModes
         * @return Iterator<Object>
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            this.lastInputs = inputs;
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "ok"))).iterator();
        }
    }

    /** Test agent that exposes a card like regular ReAct agents. */
    public static class CardBackedAgent {
        private final BaseCard card = BaseCard.builder()
            .id("card-agent")
            .name("Card Agent")
            .description("card backed")
            .build();

        /**
         * Returns the test agent card.
         *
         * @return card
         */
        public BaseCard getCard() {
            return card;
        }
    }

    /** Test agent that echoes session history across turns. */
    public static class SessionEchoAgent {
        /**
         * Streams a reply while persisting conversation history in session state.
         *
         * @param inputs the runner inputs
         * @param session the agent session
         * @param streamModes the requested stream modes
         * @return the output iterator
         */
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

    /** Test agent that uses invoke instead of streaming. */
    public static class InvokeEchoAgent {
        private final AtomicInteger invokeCount = new AtomicInteger();

        private final AtomicInteger streamCount = new AtomicInteger();

        /**
         * Invokes synchronously while persisting conversation history in session state.
         *
         * @param inputs the runner inputs
         * @param session the agent session
         * @return the invoke result map
         */
        @SuppressWarnings("unchecked")
        public Object invoke(Object inputs, Session session) {
            invokeCount.incrementAndGet();
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
            return Map.of("output", reply, "result_type", "answer");
        }

        /**
         * Streams a placeholder chunk and increments the stream counter.
         *
         * @param inputs inputs
         * @param session session
         * @param streamModes streamModes
         * @return Iterator<Object>
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            streamCount.incrementAndGet();
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "stream"))).iterator();
        }
    }

    /**
     * Test agent that emits a fixed number of stream chunks.
     */
    public static class CountingAgent {
        private final AtomicInteger nextCount = new AtomicInteger();

        /**
         * Streams five numbered chunks for cancellation tests.
         *
         * @param inputs inputs
         * @param session session
         * @param streamModes streamModes
         * @return Iterator<Object>
         */
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

    /**
     * Test agent that records session metadata from {@link AgentSessionApi}.
     */
    public static class SessionMetadataAgent {
        private String agentId;

        /**
         * Streams a single chunk and captures the resolved agent id.
         *
         * @param inputs inputs
         * @param session session
         * @param streamModes streamModes
         * @return Iterator<Object>
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            if (session instanceof AgentSessionApi agentSession) {
                this.agentId = agentSession.getAgentId();
            }
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "ok"))).iterator();
        }
    }

    private static final class RecordingRegistrar implements ExternalSvcAdapterRegistrar {
        private int registerToCalls;

        private int registerToRunnerCalls;

        @Override
        public void registerTo(RunnerConfig runnerConfig) {
            registerToCalls++;
        }

        @Override
        public void registerToRunner() {
            registerToRunnerCalls++;
        }
    }
}
