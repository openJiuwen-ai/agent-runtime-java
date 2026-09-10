/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.core.graph.pregel.PregelConstants;
import com.openjiuwen.core.graph.store.GraphStoreState;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.AgentSessionApi;
import com.openjiuwen.core.session.checkpointer.Checkpointer;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.internal.WorkflowSession;
import com.openjiuwen.core.session.state.InMemoryState;
import com.openjiuwen.core.session.state.WorkflowCommitState;
import com.openjiuwen.core.singleagent.rail.AgentCallbackContext;
import com.openjiuwen.core.singleagent.rail.AgentRail;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;
import com.openjiuwen.service.adapters.agentcore.middleware.DefaultMiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.PassthroughCredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.JedisPooledRuntimeRedisClient;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.hosting.HostedAgentDefinitions;
import com.openjiuwen.service.spec.spi.QueryStreamObserver;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.exceptions.JedisConnectionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Actual DeepAgent/Runner/checkpointer execution with a deterministic HTTP model transport.
 */
@Timeout(90)
class HostedDeepAgentIntegrationTest {
    private static final String PROBE = "hosted_checkpoint_probe";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<DeepAgent> agents = new ArrayList<>();
    private final AtomicInteger modelCalls = new AtomicInteger();
    private HttpServer model;
    private Process redisProcess;
    private JedisPooledRuntimeRedisClient redis;
    private CoreRunnerLifecycleCoordinator lifecycle;
    private Path evidence;
    private int redisPort;

    @BeforeEach
    void startModel() throws IOException {
        Runner.stop();
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(null);
        RunnerConfig.setRunnerConfig(null);
        CheckpointerFactory.setDefaultCheckpointer(new InMemoryCheckpointer());
        Path root = Path.of("target", "feat037-core-evidence").toAbsolutePath();
        Files.createDirectories(root);
        evidence = Files.createTempDirectory(root, "run-");
        model = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", this::modelResponse);
        model.start();
    }

    private void modelResponse(HttpExchange exchange) throws IOException {
        try (exchange) {
            var body = JSON.readTree(exchange.getRequestBody());
            modelCalls.incrementAndGet();
            String response;
            if (body.path("stream").asBoolean()) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                response = "data: " + JSON.writeValueAsString(modelEnvelope("chat.completion.chunk",
                        Map.of("index", 0, "delta", Map.of("role", "assistant", "content", "CHECKPOINT_MODEL_OK"))))
                        + "\n\ndata: " + JSON.writeValueAsString(modelEnvelope("chat.completion.chunk",
                        Map.of("index", 0, "delta", Map.of(), "finish_reason", "stop")))
                        + "\n\ndata: [DONE]\n\n";
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                response = JSON.writeValueAsString(modelEnvelope("chat.completion", Map.of("index", 0,
                        "message", Map.of("role", "assistant", "content", "CHECKPOINT_MODEL_OK"),
                        "finish_reason", "stop")));
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private static Map<String, Object> modelEnvelope(String type, Map<String, Object> choice) {
        return Map.of("id", "model-test", "object", type, "created", 1, "model", "test",
                "choices", List.of(choice));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @CsvSource({"memory,SYNC_REACT", "redis,SYNC_REACT", "memory,STREAM_REACT", "redis,STREAM_REACT",
            "memory,SYNC_TASK_LOOP", "redis,SYNC_TASK_LOOP", "memory,STREAM_TASK_LOOP", "redis,STREAM_TASK_LOOP"})
    void distinctCoreIdsRestoreOwnStateAndPreserveSessionReleaseBoundary(String storage, Mode mode) throws Exception {
        var firstProbe = new ProbeRail("first");
        var secondProbe = new ProbeRail("second");
        var first = agent("core-first", mode.isTaskLoop, firstProbe);
        var second = agent("core-second", mode.isTaskLoop, secondProbe);
        var handlers = startHosted(storage, first, second, !mode.isTaskLoop);
        String same = "same-" + UUID.randomUUID();
        run(handlers.get(0), same, mode);
        run(handlers.get(1), same, mode);
        run(handlers.get(1), same, mode);
        run(handlers.get(0), same, mode);
        assertThat(firstProbe.observed).containsExactly(same + ":first:1", same + ":first:2");
        assertThat(secondProbe.observed).containsExactly(same + ":second:1", same + ":second:2");
        assertThat(readState(first, same)).isEqualTo(Map.of("owner", "first", "count", 2));
        assertThat(readState(second, same)).isEqualTo(Map.of("owner", "second", "count", 2));
        assertThat(modelCalls.get()).isGreaterThanOrEqualTo(4);
        handlers.get(0).clearSession(same);
        // Existing Core release is session-wide, not confined by the caller's Handler or Core ID.
        assertThat(readState(first, same)).isNull();
        assertThat(readState(second, same)).isNull();
        String firstSession = same + "-a";
        String secondSession = same + "-b";
        run(handlers.get(0), firstSession, mode);
        run(handlers.get(1), secondSession, mode);
        handlers.get(0).clearSession(firstSession);
        assertThat(readState(first, firstSession)).isNull();
        assertThat(readState(second, secondSession)).isEqualTo(Map.of("owner", "second", "count", 1));
        // A local Handler stop must leave the shared Runner usable by another hosted Handler.
        handlers.get(0).stop();
        run(handlers.get(1), secondSession, mode);
        assertThat(readState(second, secondSession)).isEqualTo(Map.of("owner", "second", "count", 2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "redis"})
    void workflowGraphRetainsSessionScopeAndReleaseBoundary(String storage) throws Exception {
        var handlers = startHosted(storage, agent("core-first", true, new ProbeRail("first")),
                agent("core-second", true, new ProbeRail("second")), false);
        Checkpointer checkpointer = CheckpointerFactory.getCheckpointer();
        String parent = "parent-" + UUID.randomUUID();
        String derived = parent + "-child";
        saveAndRestoreWorkflows(checkpointer, parent, derived);
        // Runtime registration and the calling Core ID do not add another workflow namespace.
        handlers.get(0).clearSession(parent);
        assertThat(checkpointer.sessionExists(parent)).isFalse();
        assertThat(checkpointer.graphStore().get(parent, "workflow-first")).isEmpty();
        assertThat(checkpointer.graphStore().get(parent, "workflow-second")).isEmpty();
        assertThat(checkpointer.graphStore().get(derived, "workflow-child")).isPresent();
        var child = new WorkflowSession("workflow-child", null, derived, InMemoryState.create(), null);
        checkpointer.preWorkflowExecute(child, new InteractiveInput("resume-child"));
        assertThat(child.state().getGlobal("owner")).isEqualTo("child");
        handlers.get(1).clearSession(derived);
        assertThat(checkpointer.graphStore().get(derived, "workflow-child")).isEmpty();
    }

    private static void saveAndRestoreWorkflows(Checkpointer checkpointer, String parent, String derived) {
        saveWorkflow(checkpointer, parent, "workflow-first", "first");
        saveWorkflow(checkpointer, parent, "workflow-second", "second");
        saveWorkflow(checkpointer, derived, "workflow-child", "child");
        for (String id : List.of("workflow-first", "workflow-second")) {
            var restored = new WorkflowSession(id, null, parent, InMemoryState.create(), null);
            checkpointer.preWorkflowExecute(restored, new InteractiveInput("resume"));
            assertThat(restored.state().getGlobal("owner")).isEqualTo(id.substring("workflow-".length()));
            assertThat(checkpointer.graphStore().get(parent, id)).isPresent();
        }
    }

    private static void saveWorkflow(Checkpointer checkpointer, String conversation, String id, String owner) {
        var session = new WorkflowSession(id, null, conversation, InMemoryState.create(), null);
        checkpointer.preWorkflowExecute(session, null);
        session.state().updateGlobal(Map.of("owner", owner));
        assertInstanceOf(WorkflowCommitState.class, session.state()).commit();
        checkpointer.graphStore().save(conversation, id,
                GraphStoreState.create(id, 1, Map.of("owner", owner), List.of(), Map.of(), Map.of()));
        checkpointer.postWorkflowExecute(session, Map.of(PregelConstants.TASK_STATUS_INTERRUPT, true), null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"memory", "redis"})
    void nonTaskLoopDeepAgentInvokeRetainsItsExistingDescriptiveResponse(String storage) throws Exception {
        var firstProbe = new ProbeRail("first");
        var secondProbe = new ProbeRail("second");
        var handlers = startHosted(storage, agent("core-first", false, firstProbe),
                agent("core-second", false, secondProbe), false);
        for (var handler : handlers) {
            var response = handler.query(request("plain-invoke-" + UUID.randomUUID()));
            assertThat(response.getResult().toString()).contains("agent_name", "mode", "workspace");
            var observer = new Observer();
            handler.streamQuery(request("plain-stream-" + UUID.randomUUID()), observer);
            assertThat(observer.errors).isEmpty();
            assertThat(observer.isComplete).isTrue();
            assertThat(observer.chunks.toString()).contains("agent_name", "mode", "workspace");
        }
        assertThat(modelCalls.get()).isZero();
        assertThat(firstProbe.observed).isEmpty();
        assertThat(secondProbe.observed).isEmpty();
    }

    private DeepAgent agent(String id, boolean isTaskLoop, ProbeRail rail) {
        String workspace = evidence.resolve(id).toString();
        var config = DeepAgentConfig.builder().systemPrompt("Reply with CHECKPOINT_MODEL_OK; do not call tools.")
                .workspacePath(workspace).enableTaskLoop(isTaskLoop).maxIterations(2).completionTimeout(15.0)
                .model(Map.of("model", "test", "temperature", 0.0))
                .backend(Map.of("provider", "OpenAI", "api_key", "test-only-not-a-credential", "api_base",
                        "http://127.0.0.1:" + model.getAddress().getPort() + "/v1", "timeout", 10))
                .build();
        var agent = new DeepAgent(AgentCard.builder().id(id).name(id).description("checkpoint test").build(),
                config, Workspace.builder().rootPath(workspace).build());
        agents.add(agent);
        agent.getAgent().registerRail(rail);
        return agent;
    }

    private List<JiuwenCoreAgentHandler> startHosted(String storage, DeepAgent first, DeepAgent second,
            boolean isUnderlying) throws Exception {
        DefaultMiddlewareAdapterRegistrar registrar = null;
        if ("redis".equals(storage)) {
            startRedis();
            var props = new MiddlewareProperties();
            props.getCheckpointer().setType("redis");
            var endpoint = new MiddlewareProperties.RedisEndpoint();
            endpoint.setHost("127.0.0.1");
            endpoint.setPort(redisPort);
            props.getRedis().put("default", endpoint);
            registrar = new DefaultMiddlewareAdapterRegistrar(props, new PassthroughCredentialDecryptor(), redis);
        }
        Object firstTarget = first;
        Object secondTarget = second;
        if (isUnderlying) {
            firstTarget = first.getAgent();
            secondTarget = second.getAgent();
        }
        var a = new JiuwenCoreAgentHandler(firstTarget);
        var b = new JiuwenCoreAgentHandler(secondTarget);
        lifecycle = new CoreRunnerLifecycleCoordinator(registrar, null);
        lifecycle.prepare("test", HostedAgentDefinitions.builder().add("route-a", a).add("route-b", b).build());
        lifecycle.start();
        first.ensureInitialized();
        second.ensureInitialized();
        a.start();
        b.start();
        return List.of(a, b);
    }

    private void startRedis() throws Exception {
        String executable = System.getProperty("feat037.redis.executable", "");
        assumeTrue(!executable.isBlank(), "Set feat037.redis.executable for the real Redis cases");
        try (var socket = new ServerSocket(0)) {
            redisPort = socket.getLocalPort();
        }
        redisProcess = new ProcessBuilder(executable, "--bind", "127.0.0.1", "--port",
                String.valueOf(redisPort), "--dir", evidence.toString(), "--dbfilename", "core.rdb",
                "--appendonly", "no", "--loglevel", "warning").redirectErrorStream(true)
                .redirectOutput(evidence.resolve("redis.log").toFile()).start();
        var pooled = new JedisPooled("127.0.0.1", redisPort);
        redis = new JedisPooledRuntimeRedisClient(pooled);
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            try {
                assertThat(pooled.ping()).isEqualTo("PONG");
                return;
            } catch (JedisConnectionException pending) {
                if (!redisProcess.isAlive() || System.nanoTime() >= deadline) {
                    throw pending;
                }
                Thread.sleep(25);
            }
        }
    }

    private static Object readState(DeepAgent agent, String conversation) {
        var freshSession = new AgentSessionApi(conversation, null, agent.getCard());
        freshSession.preRun(Map.of("query", "read-checkpoint"));
        assertThat(freshSession.getSessionId()).isEqualTo(conversation);
        assertThat(freshSession.getAgentId()).isEqualTo(agent.getCard().getId());
        return freshSession.getState(PROBE);
    }

    private static ServeRequest request(String conversation) {
        var request = new ServeRequest();
        request.setConversationId(conversation);
        request.setUserId("test-user");
        request.setSpaceId("test-space");
        request.setMessages(List.of(Map.of("role", "user", "content", "Please finish this turn.")));
        return request;
    }

    private static void run(JiuwenCoreAgentHandler handler, String conversation, Mode mode) {
        if (mode.isSync) {
            var result = handler.query(request(conversation));
            assertThat(result.getConversationId()).isEqualTo(conversation);
            assertThat(result.getResult().toString()).contains("CHECKPOINT_MODEL_OK");
        } else {
            var observer = new Observer();
            handler.streamQuery(request(conversation), observer);
            assertThat(observer.errors).isEmpty();
            assertThat(observer.isComplete).isTrue();
            assertThat(observer.chunks.toString()).contains("CHECKPOINT_MODEL_OK");
        }
    }

    @AfterEach
    void closeResources() throws Exception {
        for (var agent : agents) {
            agent.destroy();
        }
        if (lifecycle != null) {
            lifecycle.stop();
        }
        Runner.stop();
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(null);
        RunnerConfig.setRunnerConfig(null);
        CheckpointerFactory.setDefaultCheckpointer(null);
        if (model != null) {
            model.stop(0);
        }
        if (redis != null) {
            redis.close();
        }
        if (redisProcess != null && redisProcess.isAlive()) {
            try (var admin = new Jedis("127.0.0.1", redisPort)) {
                admin.shutdown();
            } catch (JedisConnectionException expectedOnShutdown) {
                // Redis may close its connection before replying.
            }
            if (!redisProcess.waitFor(5, TimeUnit.SECONDS)) {
                redisProcess.destroyForcibly();
                assertThat(redisProcess.waitFor(5, TimeUnit.SECONDS)).isTrue();
            }
        }
    }

    enum Mode {
        SYNC_REACT(false, true), STREAM_REACT(false, false),
        SYNC_TASK_LOOP(true, true), STREAM_TASK_LOOP(true, false);

        private final boolean isTaskLoop;
        private final boolean isSync;

        Mode(boolean isTaskLoop, boolean isSync) {
            this.isTaskLoop = isTaskLoop;
            this.isSync = isSync;
        }
    }

    static final class ProbeRail extends AgentRail {
        private final String owner;
        private final List<String> observed = new CopyOnWriteArrayList<>();

        ProbeRail(String owner) {
            this.owner = owner;
        }

        @Override
        public void beforeInvoke(AgentCallbackContext context) {
            Object saved = context.getSession().getState(PROBE);
            int count = 1;
            if (saved instanceof Map<?, ?> previous) {
                assertThat(previous.get("owner")).isEqualTo(owner);
                count += assertInstanceOf(Number.class, previous.get("count")).intValue();
            }
            context.getSession().updateState(Map.of(PROBE, Map.of("owner", owner, "count", count)));
            observed.add(context.getSession().getSessionId() + ":" + owner + ":" + count);
        }
    }

    static final class Observer implements QueryStreamObserver {
        private final List<QueryChunk> chunks = new CopyOnWriteArrayList<>();
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();
        private volatile boolean isComplete;

        @Override
        public void onNext(QueryChunk chunk) {
            chunks.add(chunk);
        }

        @Override
        public void onError(Throwable error) {
            errors.add(error);
        }

        @Override
        public void onComplete() {
            isComplete = true;
        }
    }
}
