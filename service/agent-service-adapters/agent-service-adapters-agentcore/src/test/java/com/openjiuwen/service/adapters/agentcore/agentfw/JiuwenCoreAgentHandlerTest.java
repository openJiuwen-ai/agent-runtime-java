/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.openjiuwen.core.common.exception.BaseError;
import com.openjiuwen.core.common.exception.StatusCode;
import com.openjiuwen.core.common.schema.BaseCard;
import com.openjiuwen.core.context.ContextEngine;
import com.openjiuwen.core.controller.schema.ControllerOutput;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.runner.base.TagMatchStrategy;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.interaction.InteractionOutput;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.interrupt.InterruptRequest;
import com.openjiuwen.core.singleagent.interrupt.ToolCallInterruptRequest;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.core.workflow.WorkflowOutput;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.spec.dto.AgentFailureDescriptor;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.exception.AgentExecutionException;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    void coreErrorOutputTerminatesStreamAsFailure() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new ErrorStreamingAgent());
        List<QueryChunk> chunks = new ArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);

        handler.streamQuery(request("c-stream-error", "fail"), new QueryStreamObserver() {
            @Override
            public void onNext(QueryChunk chunk) {
                chunks.add(chunk);
            }

            @Override
            public void onError(Throwable error) {
                failure.set(error);
            }

            @Override
            public void onComplete() {
                completed.set(true);
            }
        });

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_ERROR);
            assertThat(chunk.getData()).isInstanceOfSatisfying(Map.class,
                    data -> assertThat(data).containsEntry("type", "error"));
        });
        assertThat(failure.get()).isInstanceOf(IllegalStateException.class).hasMessage("Connection refused");
        assertThat(completed.get()).isFalse();
    }

    @Test
    void queryPreservesCoreBaseErrorDescriptor() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new FailingInvokeAgent());

        assertThatThrownBy(() -> handler.query(request("c-core-error", "fail")))
                .isInstanceOfSatisfying(AgentExecutionException.class, failure -> {
                    assertThat(failure).hasMessage("model unavailable");
                    assertThat(failure.getDescriptor()).isEqualTo(
                            new AgentFailureDescriptor("MODEL_CALL_FAILED", 181001, false));
                });
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
    void nonStreamingQueryPreservesAllRemoteInterruptsInOriginalOrder() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");
        Map<String, Object> rawResult = Map.of("result_type", "interrupt", "state",
                List.of(remoteInterrupt(0, "call-a", "tool-a"), remoteInterrupt(1, "call-b", "tool-b")));

        QueryResponse response = handler.toQueryResponse(rawResult, "c-batch");

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        Map<String, Object> interrupt = (Map<String, Object>) result.get("_interrupt");
        assertThat(interrupt).containsEntry("type", "__interaction__");
        List<Map<String, Object>> items = (List<Map<String, Object>>) interrupt.get("items");
        assertThat(items).extracting(item -> item.get("toolCallId")).containsExactly("call-a", "call-b");
        assertThat(items).extracting(item -> item.get("index")).containsExactly(0, 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamingQueryEmitsOneRemoteInterruptBatch() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new ParallelInterruptAgent());
        List<QueryChunk> chunks = new ArrayList<>();

        handler.streamQuery(request("c-stream-batch", "run both"), collectingObserver(chunks));

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).getType()).isEqualTo(QueryChunk.TYPE_INTERRUPT);
        Map<String, Object> interrupt = (Map<String, Object>) chunks.get(0).getData();
        assertThat(interrupt).containsEntry("type", "__interaction__");
        List<Map<String, Object>> items = (List<Map<String, Object>>) interrupt.get("items");
        assertThat(items).extracting(item -> item.get("toolCallId")).containsExactly("call-a", "call-b");
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryDoesNotTreatBusinessBatchShapeAsInterrupt() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new BusinessItemsAgent());

        QueryResponse response = handler.query(request("c-business-items", "list items"));

        assertThat((Map<String, Object>) response.getResult()).containsEntry("content", "ok")
                .doesNotContainKey("_interrupt");
    }

    @Test
    @SuppressWarnings("unchecked")
    void nonStreamingQueryPreservesLocalInterruptPayloadsInBatch() {
        OutputSchema first = interrupt(0, "call-a", "tool-a", "ask_user");
        OutputSchema second = interrupt(1, "call-b", "tool-b", "ask_user");
        assertThat(first.getPayload()).isInstanceOfSatisfying(InteractionOutput.class,
                output -> assertThat(output.getValue()).isInstanceOfSatisfying(ToolCallInterruptRequest.class,
                        request -> request.setPayloadSchema(Map.of("type", "string"))));
        assertThat(second.getPayload()).isInstanceOfSatisfying(InteractionOutput.class,
                output -> assertThat(output.getValue()).isInstanceOfSatisfying(ToolCallInterruptRequest.class,
                        request -> request.setPayloadSchema(Map.of("type", "string"))));
        Map<String, Object> rawResult = Map.of("result_type", "interrupt", "state", List.of(first, second));
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");

        QueryResponse response = handler.toQueryResponse(rawResult, "c-local-batch");

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        Map<String, Object> interrupt = (Map<String, Object>) result.get("_interrupt");
        assertThat(interrupt).containsEntry("type", "__interaction__");
        List<Map<String, Object>> items = (List<Map<String, Object>>) interrupt.get("items");
        assertThat(items).allSatisfy(item -> {
            assertThat(item).containsEntry("type", "__interaction__").containsKey("payload");
            assertThat(item.get("payload")).isInstanceOfSatisfying(InteractionOutput.class,
                    payload -> assertThat(payload.getValue()).isInstanceOfSatisfying(ToolCallInterruptRequest.class,
                            request -> assertThat(request.getPayloadSchema()).containsEntry("type", "string")));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void singleInterruptKeepsLegacyMapShape() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");
        Map<String, Object> rawResult = Map.of("result_type", "interrupt", "state",
                List.of(remoteInterrupt(0, "call-a", "tool-a")));

        QueryResponse response = handler.toQueryResponse(rawResult, "c-single");

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        Map<String, Object> interrupt = (Map<String, Object>) result.get("_interrupt");
        assertThat(interrupt).containsEntry("toolCallId", "call-a").doesNotContainKey("items");
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildInputsNestsRemoteToolResultsInQueryForMapOnlyAgents() {
        ServeRequest request = request("c-resume", "ignored user text");
        request.setMetadata(Map.of("runtime.remoteToolResults", Map.of("call-a", "result-a", "call-b", "result-b")));

        Object inputs = JiuwenCoreAgentHandler.buildInputs(request);

        assertThat(inputs).isInstanceOf(Map.class);
        Map<String, Object> inputMap = (Map<String, Object>) inputs;
        assertThat(inputMap).containsEntry("conversation_id", "c-resume");
        assertThat(inputMap.get("query")).isInstanceOfSatisfying(InteractiveInput.class, interactiveInput -> {
            assertThat(interactiveInput.getRawInputs()).isNull();
            assertThat(interactiveInput.getUserInputs()).containsOnly(Map.entry("call-a", "result-a"),
                    Map.entry("call-b", "result-b"));
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamingResumeSupportsMapOnlyAgentSignature() {
        MapInputStreamingAgent agent = new MapInputStreamingAgent();
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agent);
        ServeRequest request = request("c-map-resume", "ignored user text");
        request.setMetadata(Map.of("runtime.remoteToolResults", Map.of("call-a", "result-a")));

        QueryResponse response = handler.query(request);

        assertThat((Map<String, Object>) response.getResult()).containsEntry("content", "resumed");
        assertThat(agent.lastInputs.get("query")).isInstanceOf(InteractiveInput.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixedInterruptKindsRemainAvailableForCallerClassification() {
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");
        OutputSchema localInterrupt = interrupt(1, "call-b", "tool-b", "ask_user");
        Map<String, Object> rawResult = Map.of("result_type", "interrupt", "state",
                List.of(remoteInterrupt(0, "call-a", "tool-a"), localInterrupt));

        QueryResponse response = handler.toQueryResponse(rawResult, "c-mixed");

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        Map<String, Object> interrupt = (Map<String, Object>) result.get("_interrupt");
        List<Map<String, Object>> items = (List<Map<String, Object>>) interrupt.get("items");
        assertThat(items).extracting(item -> item.get("toolCallId")).containsExactly("call-a", "call-b");
        assertThat(items).extracting(item -> String.valueOf(((Map<?, ?>) item.get("context")).get("_interrupt_kind")))
                .containsExactly("a2a_delegate", "ask_user");
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

    @Test
    @SuppressWarnings("unchecked")
    void workflowStringInteractionBecomesUserFacingInterruptMessage() {
        String prompt = "Agent D expense review requires manual approval for WF-001 because it exceeds policy.";
        OutputSchema interrupt = new OutputSchema("__interaction__", 0,
                new InteractionOutput("manual_approval", prompt));
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler("agent-id");

        QueryResponse response = handler
                .toQueryResponse(Map.of("result_type", "interrupt", "state", List.of(interrupt)), "c-workflow");

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertThat(result).containsEntry("content", prompt);
        assertThat((Map<String, Object>) result.get("_interrupt")).containsEntry("message", prompt)
                .containsEntry("type", "__interaction__");
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

    private static OutputSchema remoteInterrupt(int index, String toolCallId, String toolName) {
        return interrupt(index, toolCallId, toolName, "a2a_delegate");
    }

    private static OutputSchema interrupt(int index, String toolCallId, String toolName, String kind) {
        ToolCallInterruptRequest request = new ToolCallInterruptRequest();
        request.setToolCallId(toolCallId);
        request.setToolName(toolName);
        request.setMessage("message-" + toolCallId);
        request.setContext(Map.of("_interrupt_kind", kind, "agentName", toolName));
        return new OutputSchema("__interaction__", index, new InteractionOutput(toolCallId, request));
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
    void clearSessionClearsReActAgentContextEngine() {
        ReActAgent agent = mock(ReActAgent.class);
        ContextEngine contextEngine = mock(ContextEngine.class);
        when(agent.getContextEngine()).thenReturn(contextEngine);
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agent);

        handler.clearSession("c-reset-context");

        verify(contextEngine).clearContextBySession("c-reset-context");
    }

    @Test
    void clearSessionResolvesRegisteredAgentContextEngine() {
        String agentId = "registered-reset-context-agent";
        ReActAgent agent = mock(ReActAgent.class);
        ContextEngine contextEngine = mock(ContextEngine.class);
        when(agent.getContextEngine()).thenReturn(contextEngine);
        Runner.resourceMgr().addAgent(AgentCard.builder().id(agentId).name(agentId).build(), () -> agent, null);
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agentId);

        try {
            handler.clearSession("c-registered-reset-context");
            verify(contextEngine).clearContextBySession("c-registered-reset-context");
        } finally {
            Runner.resourceMgr().removeAgent(agentId, null, TagMatchStrategy.ALL, true);
        }
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

    /** Test agent that reports a Core streaming failure as an OutputSchema. */
    public static class ErrorStreamingAgent {
        /**
         * Streams one structured failure, matching ReActAgent and DeepAgent behavior.
         *
         * @param inputs inputs
         * @param session session
         * @param streamModes streamModes
         * @return Iterator<Object>
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return List.<Object>of(
                    new OutputSchema("error", 0, Map.of("output", "Connection refused", "result_type", "error")))
                    .iterator();
        }
    }

    /** Test agent that raises a structured AgentCore error. */
    public static class FailingInvokeAgent {
        /**
         * Fails synchronously with a stable Core status.
         *
         * @param inputs inputs
         * @param session session
         * @return never returns
         */
        public Object invoke(Object inputs, Session session) {
            throw new BaseError(StatusCode.MODEL_CALL_FAILED, "model unavailable", null, null);
        }
    }

    /** Test agent with the same strict Map input shape exposed by DeepAgent. */
    public static class MapInputStreamingAgent {
        private Map<String, Object> lastInputs;

        /**
         * Streams a resumed response and records the strongly typed inputs.
         *
         * @param inputs inputs
         * @param session session
         * @param streamModes streamModes
         * @return Iterator<Object>
         */
        public Iterator<Object> stream(Map<String, Object> inputs, AgentSessionApi session,
                List<StreamMode> streamModes) {
            this.lastInputs = inputs;
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "resumed"))).iterator();
        }
    }

    /** Test agent that emits two independent tool interruptions. */
    public static class ParallelInterruptAgent {
        /**
         * Streams two remote interruptions.
         *
         * @param inputs inputs
         * @param session session
         * @param streamModes stream modes
         * @return interruption iterator
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return List.<Object>of(remoteInterrupt(0, "call-a", "tool-a"), remoteInterrupt(1, "call-b", "tool-b"))
                    .iterator();
        }
    }

    /** Test agent that returns an ordinary business map matching the batch field names. */
    public static class BusinessItemsAgent {
        /**
         * Streams one ordinary business result.
         *
         * @param inputs inputs
         * @param session session
         * @param streamModes stream modes
         * @return business result iterator
         */
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            return List.<Object>of(Map.of("batchId", "business-batch", "items",
                    List.of(Map.of("name", "business-item")), "content", "ok")).iterator();
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
