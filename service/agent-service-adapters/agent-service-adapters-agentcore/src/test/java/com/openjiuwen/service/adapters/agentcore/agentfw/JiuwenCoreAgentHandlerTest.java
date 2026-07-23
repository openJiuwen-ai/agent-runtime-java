/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.common.schema.BaseCard;
import com.openjiuwen.core.controller.schema.ControllerOutput;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.interaction.InteractionOutput;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.workflow.WorkflowOutput;
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
    void cardBackedAgentKeepsLegacyStringSessionByDefault() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new CardBackedAgent());
        ServeRequest request = request("c-card-agent", "hello");
        request.setUserId("card-user");
        request.setSpaceId("card-space");
        request.setTenantId("card-tenant");

        Object session = handler.runnerSession(request);

        assertThat(session).isEqualTo("c-card-agent");
    }

    @Test
    void requestScopedCardBackedAgentSessionCarriesMergedEnvs() {
        RequestScopedHandler handler = new RequestScopedHandler(new ConfigEnvCardBackedAgent());
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
        assertThat(agentSession.getEnv("feature_flag")).isEqualTo("on");
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
    @SuppressWarnings("unchecked")
    void syncResponseExtractsCustomSingleKeyOutput() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");

        QueryResponse response = handler.toQueryResponse(Map.of("generated_report", "approved"), "c-custom");

        assertThat((Map<String, Object>) response.getResult()).containsEntry("content", "approved");
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncResponseRecursivelyExtractsNestedOutput() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");
        Map<String, Object> rawResult = Map.of("output", Map.of("generated_report", "nested"));

        QueryResponse response = handler.toQueryResponse(rawResult, "c-nested");

        assertThat((Map<String, Object>) response.getResult()).containsEntry("content", "nested");
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncResponseUnwrapsWorkflowOutput() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");
        WorkflowOutput output = new WorkflowOutput(Map.of("generated_report", "workflow"), null);

        QueryResponse response = handler.toQueryResponse(output, "c-workflow");

        assertThat((Map<String, Object>) response.getResult()).containsEntry("content", "workflow");
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncResponseUnwrapsControllerOutputMap() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");
        ControllerOutput output = new ControllerOutput("answer", Map.of("generated_report", "controller"));

        QueryResponse response = handler.toQueryResponse(output, "c-controller");

        assertThat((Map<String, Object>) response.getResult()).containsEntry("content", "controller");
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncResponsePreservesUnknownMultiKeyOutput() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");
        Map<String, Object> rawResult = new LinkedHashMap<>();
        rawResult.put("first", "one");
        rawResult.put("second", "two");

        QueryResponse response = handler.toQueryResponse(rawResult, "c-multi-key");

        assertThat((Map<String, Object>) response.getResult()).containsEntry("content", "{first=one, second=two}");
    }

    @Test
    void streamAggregationExtractsCustomSingleKeyOutput() {
        StringBuilder content = new StringBuilder();
        Map<String, Object> chunk = Map.of("type", "workflow_final", "payload",
                Map.of("output", Map.of("generated_report", "streamed")));

        JiuwenCoreAgentHandler.appendContent(chunk, content);

        assertThat(content).hasToString("streamed");
    }

    @Test
    @SuppressWarnings("unchecked")
    void syncResponseKeepsStandardOutputAndInterruptSemantics() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");

        QueryResponse standard = handler.toQueryResponse(Map.of("output", "standard", "result_type", "answer"),
                "c-standard");
        assertThat((Map<String, Object>) standard.getResult()).containsEntry("content", "standard");

        InterruptRequest request = InterruptRequest.builder().message("confirm").context(Map.of("step", 1)).build();
        OutputSchema interrupt = new OutputSchema("__interaction__", 0, new InteractionOutput("i-1", request));
        QueryResponse interrupted = handler
                .toQueryResponse(Map.of("result_type", "interrupt", "state", List.of(interrupt)), "c-interrupt");
        Map<String, Object> result = (Map<String, Object>) interrupted.getResult();
        assertThat(result).containsEntry("content", "confirm").containsKey("_interrupt");
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
        private final BaseCard card = BaseCard.builder().id("card-agent").name("Card Agent").description("card backed")
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

    /** Test handler that opts into request-scoped AgentSessionApi. */
    private static final class RequestScopedHandler extends JiuwenCoreAgentHandler {
        private RequestScopedHandler(Object agent) {
            super(agent);
        }

        @Override
        protected boolean useRequestScopedSession(ServeRequest request) {
            return true;
        }
    }

    /** Card-backed agent with config envs, mirroring Runner's String-session extraction path. */
    public static class ConfigEnvCardBackedAgent extends CardBackedAgent {
        /**
         * Returns config envs that should be merged into the explicit AgentSessionApi path.
         *
         * @return config
         */
        public ConfigWithEnvs getConfig() {
            return new ConfigWithEnvs();
        }
    }

    /** Test config object exposing envs through getEnvs(). */
    public static class ConfigWithEnvs {
        /**
         * Returns static envs for tests.
         *
         * @return envs
         */
        public Map<String, Object> getEnvs() {
            return Map.of("feature_flag", "on", "user_id", "default-user");
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
