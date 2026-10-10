/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.common.exception.BaseError;
import com.openjiuwen.core.common.exception.StatusCode;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.runner.resourcemanager.ErrorResult;
import com.openjiuwen.core.runner.resourcemanager.Ok;
import com.openjiuwen.core.runner.resourcemanager.ResourceMgr;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.common.llm.LlmModelCatalog;
import com.openjiuwen.service.adapters.common.llm.LlmModelCatalog.ModelDefinition;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.exception.AgentExecutionException;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real Core execution against a local HTTP model, with no external infrastructure.
 */
@Timeout(90)
class RequestModelSelectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    private Path workspace;
    private HttpServer server;
    private final List<DeepAgent> agents = new ArrayList<>();
    private final List<JiuwenCoreAgentHandler> handlers = new ArrayList<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private int status = 200;
    private boolean shouldDelayResponse;

    @BeforeEach
    void setup() throws Exception {
        Runner.stop().toCompletableFuture().join();
        JiuwenCoreAgentHandler.resetRunnerStarted();
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(null);
        RunnerConfig.setRunnerConfig(null);
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::respondToModelRequest);
        server.start();
    }

    private void respondToModelRequest(HttpExchange exchange) throws IOException {
        try (exchange) {
            var body = JSON.readTree(exchange.getRequestBody());
            String selected = body.path("model").asText();
            calls.add(new Call(exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Authorization"), selected));
            if (shouldDelayResponse) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    return;
                }
            }
            String response;
            if (status != 200) {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                response = "{\"error\":{\"message\":\"private-provider-payload secret-b\","
                        + "\"type\":\"invalid_request_error\"}}";
            } else if (body.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                response = "data: " + JSON.writeValueAsString(envelope("chat.completion.chunk", selected,
                        Map.of("index", 0, "delta", Map.of("role", "assistant", "content", selected))))
                        + "\n\ndata: " + JSON.writeValueAsString(envelope("chat.completion.chunk", selected,
                        Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop")))
                        + "\n\ndata: [DONE]\n\n";
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                response = JSON.writeValueAsString(envelope("chat.completion", selected,
                        Map.of("index", 0, "message", Map.of("role", "assistant", "content", selected),
                                "finish_reason", "stop")));
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @AfterEach
    void cleanup() throws Exception {
        for (var agent : agents) {
            agent.shutdown();
        }
        for (var handler : handlers) {
            handler.stop();
        }
        Runner.stop().toCompletableFuture().join();
        JiuwenCoreAgentHandler.resetRunnerStarted();
        server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void selectedEndpointKeyAndVendorModelThenReturnToDefault(boolean isDeep, boolean isStreaming) {
        var handler = handler(isDeep, "instance", catalog());
        handler.start(); // Registration must be idempotent.
        var selectedRequest = request(" b ");
        run(handler, selectedRequest, isStreaming, "vendor-b");
        var defaultRequest = request(null);
        defaultRequest.setConversationId(selectedRequest.getConversationId());
        run(handler, defaultRequest, isStreaming, "vendor-a");
        assertThat(calls).containsExactly(new Call("/b/chat/completions", "Bearer secret-b", "vendor-b"),
                new Call("/a/chat/completions", "Bearer secret-a", "vendor-a"));
    }

    @ParameterizedTest
    @CsvSource({"false,false,401", "false,true,401", "true,false,401", "true,true,401",
            "false,false,500", "false,true,500", "true,false,500", "true,true,500"})
    void finalHttpFailureIsRedactedAndNeverCompletesSuccessfully(boolean isDeep, boolean isStreaming,
            int failureStatus) {
        status = failureStatus;
        var handler = handler(isDeep, "instance", catalog());
        if (isStreaming) {
            var observer = new Observer();
            handler.streamQuery(request("b"), observer);
            assertThat(observer.errors).hasSize(1);
            assertThat(observer.completions).isZero();
            assertThat(observer.chunks).filteredOn(chunk -> QueryChunk.TYPE_ERROR.equals(chunk.getType()))
                    .singleElement().satisfies(chunk -> {
                        if (!(chunk.getData() instanceof Map<?, ?> data)) {
                            throw new AssertionError("Expected a structured stream error");
                        }
                        assertThat(data.get("type")).isEqualTo("error");
                        assertThat(chunk.getData().toString()).doesNotContain("secret-b", "private-provider-payload");
                    });
        } else {
            assertThatThrownBy(() -> handler.query(request("b")))
                    .isInstanceOf(AgentExecutionException.class).hasMessage("Agent execution failed");
        }
        assertThat(calls).isNotEmpty().allSatisfy(call -> {
            assertThat(call.path()).isEqualTo("/b/chat/completions");
            assertThat(call.model()).isEqualTo("vendor-b");
        });
    }

    @Test
    void concurrentReActSessionsSelectIndependently() {
        var handler = handler(false, "instance", catalog());
        var a = CompletableFuture.runAsync(() -> run(handler, request("a"), false, "vendor-a"));
        var b = CompletableFuture.runAsync(() -> run(handler, request("b"), true, "vendor-b"));
        CompletableFuture.allOf(a, b).join();
        assertThat(calls).containsExactlyInAnyOrder(
                new Call("/a/chat/completions", "Bearer secret-a", "vendor-a"),
                new Call("/b/chat/completions", "Bearer secret-b", "vendor-b"));
    }

    @Test
    void unknownOrDisabledSelectionDoesNotCallLlm() {
        var enabled = handler(false, "instance", catalog());
        assertThatThrownBy(() -> enabled.query(request("unknown"))).isInstanceOf(AgentExecutionException.class);
        var disabled = handler(false, "legacy", null);
        assertThatThrownBy(() -> disabled.query(request("b"))).isInstanceOf(RuntimeException.class);
        assertThat(calls).isEmpty();
        assertThat(captureExecutionInputs("legacy", null, request(null))).doesNotContainKey("model_id");
    }

    @Test
    void sameAliasInDifferentScopesUsesItsOwnDefinition() {
        var first = handler(false, "first", catalog());
        var otherCatalog = new LlmModelCatalog("a", Map.of("a", definition("b")), 0.0, 0.8, Duration.ofSeconds(2));
        var second = handler(false, "second", otherCatalog);
        run(first, request("a"), false, "vendor-a");
        run(second, request("a"), false, "vendor-b");
        assertThat(calls).extracting(Call::path).containsExactly("/a/chat/completions", "/b/chat/completions");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void connectionFailureAndTimeoutSurfaceAsExecutionFailures(boolean isTimeout) {
        shouldDelayResponse = isTimeout;
        var catalog = new LlmModelCatalog("a", catalog().models(), 0.0, 0.8, Duration.ofMillis(50));
        var handler = handler(false, "instance", catalog);
        if (!isTimeout) {
            server.stop(0);
        }
        assertThatThrownBy(() -> handler.query(request("b")))
                .isInstanceOf(AgentExecutionException.class).hasMessage("Agent execution failed");
        if (isTimeout) {
            assertThat(calls).isNotEmpty().allSatisfy(call -> assertThat(call.model()).isEqualTo("vendor-b"));
        }
    }

    @Test
    void toolResumePreservesSelectionAndRejectsCoreAliases() {
        var request = request("b");
        request.setMetadata(Map.of("runtime.remoteToolResults", Map.of("call-a", "answer"),
                "dynamic_model_id", "forged", "target_model_id", "forged"));
        var inputs = captureExecutionInputs("instance", catalog(), request);
        assertThat(inputs.get("query")).isInstanceOf(InteractiveInput.class);
        assertThat(inputs.get("model_id")).isEqualTo("runtime:model:aW5zdGFuY2U:Yg");
        assertThat(inputs).doesNotContainKeys("dynamic_model_id", "target_model_id");
    }

    @Test
    void businessErrorsAndRecoveredHistoricalRoundsAreNotFailures() {
        var handler = handler(true, "instance", catalog());
        assertThat(handler.toQueryResponse(Map.of("error", "business-field", "output", "valid"), "business"))
                .isNotNull();
        assertThat(handler.toQueryResponse(Map.of("type", "deep_agent_result", "result_type", "answer",
                "rounds", List.of(Map.of("error", "old-failure")),
                "final_result", Map.of("result_type", "answer", "output", "recovered")), "recovered"))
                .isNotNull();
    }

    @Test
    void conflictRollsBackOnlyNewResourcesAndKeepsOriginalOwnerUsable() {
        var first = handler(false, "instance", new LlmModelCatalog("b", Map.of("b", definition("b")),
                0.0, 0.8, Duration.ofSeconds(2)));
        assertConflictingCatalogRollsBack();
        var replacement = new JiuwenCoreAgentHandler("unused", null, null, "instance",
                new LlmModelCatalog("a", Map.of("a", definition("a")), 0.0, 0.8, Duration.ofSeconds(2)));
        replacement.start(); // Proves rollback removed A and did not leave a half catalog.
        run(first, request("b"), false, "vendor-b");
    }

    private void assertConflictingCatalogRollsBack() {
        var ordered = new LinkedHashMap<String, ModelDefinition>();
        ordered.put("a", definition("a"));
        ordered.put("b", definition("b"));
        var conflict = new JiuwenCoreAgentHandler("unused", null, null, "instance",
                new LlmModelCatalog("a", ordered, 0.0, 0.8, Duration.ofSeconds(2)));
        assertThatThrownBy(conflict::start).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id=runtime:model:aW5zdGFuY2U:Yg")
                .hasMessageContaining("RESOURCE_ADD_ERROR");
    }

    @Test
    void registrationDiagnosticsKeepSafeDetails() {
        var definitions = new LinkedHashMap<String, ModelDefinition>();
        definitions.put("a", definition("a"));
        definitions.put("b", definition("b"));
        var handler = new JiuwenCoreAgentHandler("unused", null, null, "instance",
                new LlmModelCatalog("a", definitions, 0.0, 0.8, Duration.ofSeconds(2)));
        new CoreRunnerLifecycleCoordinator(null, null).prepare("test",
                HostedAgentDefinitions.builder().add("agent", handler).build());
        var resources = org.mockito.Mockito.mock(ResourceMgr.class);
        var registrationError = new BaseError(StatusCode.RESOURCE_ADD_ERROR, "secret-b private-provider-payload",
                null, null);
        org.mockito.Mockito.doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            if (id.endsWith(":Yg")) {
                return new ErrorResult<>(registrationError);
            }
            Object model = invocation.<java.util.function.Supplier<?>>getArgument(1).get();
            org.mockito.Mockito.doReturn(CompletableFuture.completedFuture(model)).when(resources).getModel(id);
            return new Ok<>(id);
        }).when(resources).addModel(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        var rollbackError = new IllegalStateException("secret-a private-provider-payload");
        org.mockito.Mockito.doThrow(rollbackError).when(resources)
                .removeModelForce("runtime:model:aW5zdGFuY2U:YQ", true);
        try (var runner = org.mockito.Mockito.mockStatic(Runner.class)) {
            runner.when(Runner::resourceMgr).thenReturn(resources);
            assertThatThrownBy(handler::start).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("registration").hasMessageContaining("RESOURCE_ADD_ERROR")
                    .hasMessageContaining("code=" + registrationError.getCode())
                    .satisfies(failure -> {
                        assertThat(failure.getStackTrace()).containsExactly(registrationError.getStackTrace());
                        assertThat(failure.getSuppressed()).singleElement().satisfies(rollback -> {
                            assertThat(rollback).hasMessageContaining("rollback")
                                    .hasMessageContaining("id=runtime:model:aW5zdGFuY2U:YQ")
                                    .hasMessageContaining("java.lang.IllegalStateException");
                            assertThat(rollback.getStackTrace()).containsExactly(rollbackError.getStackTrace());
                        });
                        assertSanitizedDiagnostic(failure);
                        for (Throwable suppressed : failure.getSuppressed()) {
                            assertSanitizedDiagnostic(suppressed);
                        }
                    });
        }
    }

    private static void assertSanitizedDiagnostic(Throwable failure) {
        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage()).doesNotContain("secret-a", "secret-b", "private-provider-payload");
    }

    @Test
    void unexpectedRegistrationFailureRollsBackAllOwnedModelsDespiteCleanupError() {
        var definitions = new LinkedHashMap<String, ModelDefinition>();
        definitions.put("a", definition("a"));
        definitions.put("b", definition("b"));
        definitions.put("c", definition("b"));
        var handler = new JiuwenCoreAgentHandler("unused", null, null, "instance",
                new LlmModelCatalog("a", definitions, 0.0, 0.8, Duration.ofSeconds(2)));
        var resources = org.mockito.Mockito.mock(ResourceMgr.class);
        var failure = new UnsupportedOperationException("registration unavailable");
        org.mockito.Mockito.doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            if (id.endsWith(":Yw")) {
                throw failure;
            }
            Object model = invocation.<java.util.function.Supplier<?>>getArgument(1).get();
            org.mockito.Mockito.doReturn(CompletableFuture.completedFuture(model)).when(resources).getModel(id);
            return new Ok<>(id);
        }).when(resources).addModel(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doThrow(new AssertionError("secret-a")).when(resources)
                .removeModelForce("runtime:model:aW5zdGFuY2U:YQ", true);
        try (var runner = org.mockito.Mockito.mockStatic(Runner.class)) {
            runner.when(Runner::resourceMgr).thenReturn(resources);
            runner.when(Runner::stop).thenThrow(new AssertionError("stop unavailable"));
            assertThatThrownBy(handler::start).isSameAs(failure).satisfies(error -> {
                assertThat(error.getSuppressed()).hasSize(2);
                assertSanitizedDiagnostic(error.getSuppressed()[0]);
            });
            assertThat(JiuwenCoreAgentHandler.isRunnerStarted()).isFalse();
            org.mockito.Mockito.verify(resources).removeModelForce("runtime:model:aW5zdGFuY2U:YQ", true);
            org.mockito.Mockito.verify(resources).removeModelForce("runtime:model:aW5zdGFuY2U:Yg", true);
            org.mockito.Mockito.verify(resources, org.mockito.Mockito.never())
                    .removeModelForce("runtime:model:aW5zdGFuY2U:Yw", true);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deepFinalStreamFailureAfterPartialOutputTerminatesOnce(boolean isWrapped) {
        Map<String, Object> failure = Map.of("type", "deep_agent_result",
                "final_result", Map.of("error", "completion_timeout secret-b"));
        Object finalChunk = isWrapped
                ? new OutputSchema("answer", 1, Map.of("result_type", "answer", "output", failure)) : failure;
        var handler = finalResultStreamHandler(true, catalog(), finalChunk);
        var observer = new Observer();
        handler.streamQuery(request("b"), observer);
        assertThat(observer.errors).singleElement().satisfies(error ->
                assertThat(error).hasMessage("Agent execution failed"));
        assertThat(observer.completions).isZero();
        assertThat(observer.chunks).hasSize(2);
        assertThat(observer.chunks.get(0).getData().toString()).contains("partial");
        assertThat(observer.chunks.get(1).getType()).isEqualTo(QueryChunk.TYPE_ERROR);
        assertThat(observer.chunks.get(1).getData().toString()).contains("error").doesNotContain("secret-b");
    }

    @ParameterizedTest
    @CsvSource({"true,true", "false,true", "true,false"})
    void finalStreamRecognitionPreservesBusinessResultsAndLegacyBehavior(boolean isDeep, boolean isEnabled) {
        Map<String, Object> finalResult = isDeep && isEnabled
                ? Map.of("result_type", "answer", "output", "recovered", "error", "business-field")
                : Map.of("error", "business-field");
        Object answer = new OutputSchema("answer", 1, Map.of("result_type", "answer", "output",
                Map.of("type", "deep_agent_result", "rounds", List.of(Map.of("error", "old-failure")),
                        "final_result", finalResult)));
        var handler = finalResultStreamHandler(isDeep, isEnabled ? catalog() : null, answer);
        var observer = new Observer();
        handler.streamQuery(request(isEnabled ? "b" : null), observer);
        assertThat(observer.errors).isEmpty();
        assertThat(observer.completions).isOne();
        assertThat(observer.chunks).hasSize(3).allSatisfy(chunk ->
                assertThat(chunk.getType()).isEqualTo(QueryChunk.TYPE_CHUNK));
    }

    private JiuwenCoreAgentHandler finalResultStreamHandler(boolean isDeep, LlmModelCatalog catalog, Object answer) {
        Object agent = isDeep ? org.mockito.Mockito.mock(DeepAgent.class) : new Object();
        var handler = new JiuwenCoreAgentHandler(agent, null, null, "instance", catalog) {
            @Override
            protected Iterator<Object> executeAgentStreaming(Map<String, Object> inputs, Object session,
                    List<StreamMode> modes) {
                return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "partial")),
                        answer, new OutputSchema("llm_output", 2, Map.of("content", "trailing"))).iterator();
            }
        };
        handlers.add(handler);
        handler.start();
        return handler;
    }

    @Test
    void invalidProviderFailsStartupAndReleasesRunnerOwnedByFailedHandler() {
        var models = new LinkedHashMap<String, ModelDefinition>();
        models.put("a", definition("a"));
        models.put("b", new ModelDefinition("unregistered-provider", "test", base("b"), "vendor-b", true));
        var invalid = new JiuwenCoreAgentHandler("unused", null, null, "instance",
                new LlmModelCatalog("a", models, 0.0, 0.8, Duration.ofSeconds(2)));
        assertThatThrownBy(invalid::start).isInstanceOf(RuntimeException.class);
        assertThat(JiuwenCoreAgentHandler.isRunnerStarted()).isFalse();
        var valid = handler(false, "instance", catalog());
        run(valid, request("b"), false, "vendor-b");
    }

    @Test
    void hostedRegistrationAndLocalStopKeepSharedCatalogAlive() {
        var a = new JiuwenCoreAgentHandler("hosted-a", null, null, "hosted-a", catalog());
        var b = new JiuwenCoreAgentHandler("hosted-b", null, null, "hosted-b", catalog());
        var coordinator = new CoreRunnerLifecycleCoordinator(null, null);
        coordinator.prepare("test", HostedAgentDefinitions.builder().add("a", a).add("b", b).build());
        coordinator.start();
        try {
            a.start();
            b.start();
            a.start();
            String id = "runtime:model:aG9zdGVkLWI:Yg";
            Object model = Runner.resourceMgr().getModel(id).toCompletableFuture().join();
            a.clearSession("unused-conversation");
            a.stop();
            assertThat(Runner.resourceMgr().getModel(id).toCompletableFuture().join()).isSameAs(model);
        } finally {
            coordinator.stop();
        }
    }

    @Test
    void standaloneStopAndRestartRegistersItsCatalogAgain() {
        var handler = new JiuwenCoreAgentHandler("unused", null, null, "instance", catalog());
        handlers.add(handler);
        handler.start();
        String id = "runtime:model:aW5zdGFuY2U:Yg";
        Object original = Runner.resourceMgr().getModel(id).toCompletableFuture().join();
        handler.stop();
        handler.start();
        assertThat(Runner.resourceMgr().getModel(id).toCompletableFuture().join()).isNotNull().isNotSameAs(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "next", "error", "complete"})
    void observerExceptionsNeverDuplicateTerminalNotification(String throwingCallback) {
        var handler = streamingCallbackHandler(throwingCallback);
        handlers.add(handler);
        handler.start();
        var observer = throwingObserver(throwingCallback);
        try {
            handler.streamQuery(request("b"), observer);
        } catch (IllegalStateException expectedObserverFailure) {
            assertThat(expectedObserverFailure).hasMessageStartingWith("observer");
        }
        if ("complete".equals(throwingCallback)) {
            assertThat(observer.completions).isOne();
            assertThat(observer.errors).isEmpty();
        } else {
            assertThat(observer.errors).hasSize(1);
            assertThat(observer.completions).isZero();
            assertThat(observer.chunks).filteredOn(chunk -> QueryChunk.TYPE_ERROR.equals(chunk.getType())).hasSize(1);
            assertThat(observer.chunks.toString()).doesNotContain("secret-b");
        }
    }

    private JiuwenCoreAgentHandler streamingCallbackHandler(String throwingCallback) {
        return new JiuwenCoreAgentHandler("unused", null, null, "instance", catalog()) {
            /**
             * {@inheritDoc}
             */
            @Override
            protected Iterator<Object> executeAgentStreaming(Map<String, Object> inputs, Object session,
                    List<StreamMode> modes) {
                if ("complete".equals(throwingCallback)) {
                    return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "OK"))).iterator();
                }
                return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", "partial")),
                        new OutputSchema("answer", 1, Map.of("result_type", "error", "output", "secret-b")))
                        .iterator();
            }
        };
    }

    private static Observer throwingObserver(String throwingCallback) {
        return new Observer() {
            /**
             * {@inheritDoc}
             */
            @Override
            public void onNext(QueryChunk chunk) {
                super.onNext(chunk);
                if ("next".equals(throwingCallback) && QueryChunk.TYPE_ERROR.equals(chunk.getType())) {
                    throw new IllegalStateException("observer next");
                }
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onError(Throwable error) {
                super.onError(error);
                if ("error".equals(throwingCallback)) {
                    throw new IllegalStateException("observer error");
                }
            }

            /**
             * {@inheritDoc}
             */
            @Override
            public void onComplete() {
                super.onComplete();
                if ("complete".equals(throwingCallback)) {
                    throw new IllegalStateException("observer complete");
                }
            }
        };
    }

    private Map<String, Object> captureExecutionInputs(String scope, LlmModelCatalog catalog, ServeRequest request) {
        var captured = new AtomicReference<Map<String, Object>>();
        var handler = new JiuwenCoreAgentHandler("unused", null, null, scope, catalog) {
            /**
             * {@inheritDoc}
             */
            @Override
            protected Iterator<Object> executeAgentStreaming(Map<String, Object> inputs, Object session,
                    List<StreamMode> modes) {
                captured.set(inputs);
                return List.of().iterator();
            }
        };
        handlers.add(handler);
        handler.start();
        var observer = new Observer();
        handler.streamQuery(request, observer);
        assertThat(observer.errors).isEmpty();
        assertThat(observer.completions).isOne();
        assertThat(captured.get()).isNotNull();
        return captured.get();
    }

    private JiuwenCoreAgentHandler handler(boolean isDeep, String scope, LlmModelCatalog catalog) {
        String id = "agent-" + UUID.randomUUID();
        String path = workspace.resolve(id).toString();
        var config = DeepAgentConfig.builder().systemPrompt("Reply once; do not call tools.")
                .workspacePath(path).enableTaskLoop(isDeep).maxIterations(1).completionTimeout(20.0)
                .model(Map.of("model", "vendor-a", "temperature", 0.0))
                .backend(Map.of("provider", "OpenAI", "api_key", "secret-a", "api_base", base("a"), "timeout", 2))
                .build();
        var agent = new DeepAgent(AgentCard.builder().id(id).name(id).build(), config,
                Workspace.builder().rootPath(path).build());
        agents.add(agent);
        var handler = new JiuwenCoreAgentHandler(isDeep ? agent : agent.getAgent(), null, null, scope, catalog);
        handlers.add(handler);
        handler.start();
        agent.ensureInitialized();
        if (!(agent.getAgent().getConfig() instanceof ReActAgentConfig reactConfig)) {
            throw new AssertionError("Expected a ReAct configuration");
        }
        reactConfig.configureStreamRetry(0, 0);
        return handler;
    }

    private LlmModelCatalog catalog() {
        return new LlmModelCatalog("a", Map.of("a", definition("a"), "b", definition("b")),
                0.0, 0.8, Duration.ofSeconds(2));
    }

    private ModelDefinition definition(String name) {
        return new ModelDefinition("OpenAI", "secret-" + name, base(name), "vendor-" + name, true);
    }

    private String base(String name) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/" + name;
    }

    private static ServeRequest request(String model) {
        var request = new ServeRequest();
        request.setConversationId(UUID.randomUUID().toString());
        request.setMessages(List.of(Map.of("role", "user", "content", "reply once")));
        request.setModelName(model);
        return request;
    }

    private static void run(JiuwenCoreAgentHandler handler, ServeRequest request, boolean isStreaming,
            String expected) {
        if (isStreaming) {
            var observer = new Observer();
            handler.streamQuery(request, observer);
            assertThat(observer.errors).isEmpty();
            assertThat(observer.completions).isEqualTo(1);
            assertThat(observer.chunks.toString()).contains(expected);
        } else {
            assertThat(handler.query(request).getResult().toString()).contains(expected);
        }
    }

    private static Map<String, Object> envelope(String type, String model, Map<String, Object> choice) {
        return Map.of("id", "selection-test", "object", type, "created", 1, "model", model, "choices", List.of(choice));
    }

    private record Call(String path, String authorization, String model) {
        // Immutable record of each model endpoint request.
    }

    private static class Observer implements QueryStreamObserver {
        final List<QueryChunk> chunks = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        int completions;

        /**
         * Records an emitted chunk.
         *
         * @param chunk emitted chunk
         */
        @Override
        public void onNext(QueryChunk chunk) {
            chunks.add(chunk);
        }

        /**
         * Records terminal failure.
         *
         * @param error terminal failure
         */
        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }

        /**
         * Records successful completion.
         */
        @Override
        public void onComplete() {
            completions++;
        }
    }
}
