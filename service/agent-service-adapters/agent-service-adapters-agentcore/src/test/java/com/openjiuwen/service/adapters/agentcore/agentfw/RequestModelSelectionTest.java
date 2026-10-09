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
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

/** Real Core execution against a local HTTP model, with no external infrastructure. */
@Timeout(90)
class RequestModelSelectionTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path workspace;
    private HttpServer server;
    private final List<DeepAgent> agents = new ArrayList<>();
    private final List<JiuwenCoreAgentHandler> handlers = new ArrayList<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private int status = 200;
    private boolean delayResponse;

    @BeforeEach
    void setup() throws Exception {
        Runner.stop().toCompletableFuture().join();
        JiuwenCoreAgentHandler.resetRunnerStarted();
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(null);
        RunnerConfig.setRunnerConfig(null);
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                var body = JSON.readTree(exchange.getRequestBody());
                String selected = body.path("model").asText();
                calls.add(new Call(exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders().getFirst("Authorization"), selected));
                if (delayResponse) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                String response;
                if (status != 200) {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    response = "{\"error\":{\"message\":\"private-provider-payload secret-b\",\"type\":\"invalid_request_error\"}}";
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
        });
        server.start();
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
    void selectedEndpointKeyAndVendorModelThenReturnToDefault(boolean deep, boolean stream) {
        var handler = handler(deep, "instance", catalog());
        handler.start(); // Registration must be idempotent.
        var selectedRequest = request(" b ");
        run(handler, selectedRequest, stream, "vendor-b");
        var defaultRequest = request(null);
        defaultRequest.setConversationId(selectedRequest.getConversationId());
        run(handler, defaultRequest, stream, "vendor-a");
        assertThat(calls).containsExactly(new Call("/b/chat/completions", "Bearer secret-b", "vendor-b"),
                new Call("/a/chat/completions", "Bearer secret-a", "vendor-a"));
    }

    @ParameterizedTest
    @CsvSource({"false,false,401", "false,true,401", "true,false,401", "true,true,401",
            "false,false,500", "false,true,500", "true,false,500", "true,true,500"})
    void finalHttpFailureIsRedactedAndNeverCompletesSuccessfully(boolean deep, boolean stream, int failureStatus) {
        status = failureStatus;
        var handler = handler(deep, "instance", catalog());
        if (stream) {
            var observer = new Observer();
            handler.streamQuery(request("b"), observer);
            assertThat(observer.errors).hasSize(1);
            assertThat(observer.completions).isZero();
            assertThat(observer.chunks).filteredOn(chunk -> QueryChunk.TYPE_ERROR.equals(chunk.getType()))
                    .singleElement().satisfies(chunk -> {
                        assertThat(chunk.getData()).isInstanceOf(Map.class);
                        assertThat(((Map<?, ?>) chunk.getData()).get("type")).isEqualTo("error");
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
    void connectionFailureAndTimeoutSurfaceAsExecutionFailures(boolean timeout) {
        delayResponse = timeout;
        var catalog = new LlmModelCatalog("a", catalog().models(), 0.0, 0.8, Duration.ofMillis(50));
        var handler = handler(false, "instance", catalog);
        if (!timeout) {
            server.stop(0);
        }
        assertThatThrownBy(() -> handler.query(request("b")))
                .isInstanceOf(AgentExecutionException.class).hasMessage("Agent execution failed");
        if (timeout) {
            assertThat(calls).isNotEmpty().allSatisfy(call -> assertThat(call.model()).isEqualTo("vendor-b"));
        }
    }

    @Test
    void toolResumePreservesSelectionAndCannotInjectHigherPriorityCoreAliases() {
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
        var ordered = new LinkedHashMap<String, ModelDefinition>();
        ordered.put("a", definition("a"));
        ordered.put("b", definition("b"));
        var conflict = new JiuwenCoreAgentHandler("unused", null, null, "instance",
                new LlmModelCatalog("a", ordered, 0.0, 0.8, Duration.ofSeconds(2)));
        assertThatThrownBy(conflict::start).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("id=runtime:model:aW5zdGFuY2U:Yg")
                .hasMessageContaining("RESOURCE_ADD_ERROR");
        var replacement = new JiuwenCoreAgentHandler("unused", null, null, "instance",
                new LlmModelCatalog("a", Map.of("a", definition("a")), 0.0, 0.8, Duration.ofSeconds(2)));
        replacement.start(); // Proves rollback removed A and did not leave a half catalog.
        run(first, request("b"), false, "vendor-b");
    }

    @Test
    void registrationAndRollbackDiagnosticsRetainSafeDetailsWithoutSecrets() {
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
        var rollbackError = new IllegalStateException("secret-a private-provider-payload");
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
                        var output = new java.io.StringWriter();
                        failure.printStackTrace(new java.io.PrintWriter(output));
                        assertThat(output.toString()).doesNotContain("secret-a", "secret-b", "private-provider-payload");
                    });
        }
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
        var handler = new JiuwenCoreAgentHandler("unused", null, null, "instance", catalog()) {
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
        handlers.add(handler);
        handler.start();
        var observer = new Observer() {
            @Override
            public void onNext(QueryChunk chunk) {
                super.onNext(chunk);
                if ("next".equals(throwingCallback) && QueryChunk.TYPE_ERROR.equals(chunk.getType())) {
                    throw new IllegalStateException("observer next");
                }
            }
            @Override
            public void onError(Throwable error) {
                super.onError(error);
                if ("error".equals(throwingCallback)) {
                    throw new IllegalStateException("observer error");
                }
            }
            @Override
            public void onComplete() {
                super.onComplete();
                if ("complete".equals(throwingCallback)) {
                    throw new IllegalStateException("observer complete");
                }
            }
        };
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

    private Map<String, Object> captureExecutionInputs(String scope, LlmModelCatalog catalog, ServeRequest request) {
        var captured = new AtomicReference<Map<String, Object>>();
        var handler = new JiuwenCoreAgentHandler("unused", null, null, scope, catalog) {
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

    private JiuwenCoreAgentHandler handler(boolean deep, String scope, LlmModelCatalog catalog) {
        String id = "agent-" + UUID.randomUUID();
        String path = workspace.resolve(id).toString();
        var config = DeepAgentConfig.builder().systemPrompt("Reply once; do not call tools.")
                .workspacePath(path).enableTaskLoop(deep).maxIterations(1).completionTimeout(20.0)
                .model(Map.of("model", "vendor-a", "temperature", 0.0))
                .backend(Map.of("provider", "OpenAI", "api_key", "secret-a", "api_base", base("a"), "timeout", 2))
                .build();
        var agent = new DeepAgent(AgentCard.builder().id(id).name(id).build(), config,
                Workspace.builder().rootPath(path).build());
        agents.add(agent);
        var handler = new JiuwenCoreAgentHandler(deep ? agent : agent.getAgent(), null, null, scope, catalog);
        handlers.add(handler);
        handler.start();
        agent.ensureInitialized();
        ((ReActAgentConfig) agent.getAgent().getConfig()).configureStreamRetry(0, 0);
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

    private static void run(JiuwenCoreAgentHandler handler, ServeRequest request, boolean stream, String expected) {
        if (stream) {
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

    private record Call(String path, String authorization, String model) { }

    private static class Observer implements QueryStreamObserver {
        final List<QueryChunk> chunks = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        int completions;
        public void onNext(QueryChunk chunk) { chunks.add(chunk); }
        public void onError(Throwable error) { errors.add(error); }
        public void onComplete() { completions++; }
    }
}
