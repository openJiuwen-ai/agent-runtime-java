/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.service.adapters.agentcore.agentfw;

import com.openjiuwen.core.foundation.store.kv.InMemoryKVStore;
import com.openjiuwen.core.foundation.tool.Tool;
import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.rails.TaskPlanningRail;
import com.openjiuwen.harness.tools.ToolOutput;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.autoconfigure.MiddlewareAdaptersAutoConfiguration;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisMiddlewareAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DeepAgentTodoDefaultsTest {
    private final Map<String, Object> previous = RunnerConfig.getRunnerConfig().getCheckpointerConfig();

    @AfterEach
    void restoreConfig() {
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(previous);
    }

    private DeepAgent agent(DeepAgentConfig config) {
        DeepAgent agent = mock(DeepAgent.class);
        when(agent.getConfig()).thenReturn(config);
        return agent;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void acceptsBothRegistrarConstructorForms(boolean suppliedRegistrar) {
        DeepAgentConfig config = DeepAgentConfig.builder()
                .todoStorageConfig(Map.of("ttl", Map.of("default_ttl", 2))).build();
        DeepAgent agent = agent(config);
        MiddlewareAdapterRegistrar registrar = runner -> runner.setCheckpointerConfig(Map.of("type", "redis"));
        // Auto-configuration publishes the same effective settings when no registrar is passed.
        registrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig());
        JiuwenCoreAgentHandler handler = suppliedRegistrar
                ? new JiuwenCoreAgentHandler(agent, registrar) : new JiuwenCoreAgentHandler(agent);
        handler.prepareAgentForExecution(agent);
        assertThat(config.getTodoStorageType()).isEqualTo("checkpointer_redis");
        assertThat(config.getTodoStorageConfig()).containsEntry("ttl", Map.of("default_ttl", 2));
        verify(agent, never()).setKvStore(any());
        verify(agent, never()).ensureInitialized();
    }

    @Test
    void springAutoConfigurationSupportsHandlerWithoutRegistrar() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                CredentialDecryptorAutoConfiguration.class, RedisMiddlewareAutoConfiguration.class,
                MiddlewareAdaptersAutoConfiguration.class))
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                        "openjiuwen.service.middleware.redis.default.host=localhost")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    DeepAgentConfig config = new DeepAgentConfig();
                    DeepAgent agent = agent(config);
                    new JiuwenCoreAgentHandler(agent).prepareAgentForExecution(agent);
                    assertThat(config.getTodoStorageType()).isEqualTo("checkpointer_redis");
                });
    }

    @Test
    void keepsExplicitStorageChoicesAndIndependentStores() {
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(Map.of("type", "redis"));
        for (String type : List.of("file", "kv", "custom", "checkpointer_redis")) {
            DeepAgentConfig config = DeepAgentConfig.builder().todoStorageType(type).build();
            DeepAgent agent = agent(config);
            new JiuwenCoreAgentHandler(agent).prepareAgentForExecution(agent);
            assertThat(config.getTodoStorageType()).isEqualTo(type);
        }
        DeepAgentConfig independent = DeepAgentConfig.builder()
                .kvStoreConfig(Map.of("type", "redis")).build();
        DeepAgent agent = agent(independent);
        new JiuwenCoreAgentHandler(agent).prepareAgentForExecution(agent);
        assertThat(independent.isTodoStorageTypeExplicit()).isFalse();
        DeepAgentConfig injected = new DeepAgentConfig();
        DeepAgent injectedAgent = agent(injected);
        when(injectedAgent.getKvStore()).thenReturn(new InMemoryKVStore());
        new JiuwenCoreAgentHandler(injectedAgent).prepareAgentForExecution(injectedAgent);
        assertThat(injected.isTodoStorageTypeExplicit()).isFalse();
    }

    @Test
    void keepsPreviouslyInitializedDefaultFileWithoutError() {
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(Map.of("type", "redis"));
        DeepAgentConfig config = new DeepAgentConfig();
        DeepAgent agent = agent(config);
        when(agent.isInitialized()).thenReturn(true);
        new JiuwenCoreAgentHandler(agent).prepareAgentForExecution(agent);
        assertThat(config.getTodoStorageType()).isEqualTo("file");
        assertThat(config.isTodoStorageTypeExplicit()).isFalse();
        verify(agent, never()).getConfig();
    }

    @Test
    void actualInitializedFileTodoKeepsItsToolsAndData(@TempDir Path workspace) throws Exception {
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(Map.of("type", "redis"));
        DeepAgentConfig config = DeepAgentConfig.builder().workspacePath(workspace.toString())
                .rails(List.of(new TaskPlanningRail())).build();
        try (DeepAgent agent = HarnessFactory.createDeepAgent(config)) {
            agent.ensureInitialized();
            List<Object> tools = List.copyOf(agent.getRegisteredTools());
            Tool create = tools.stream().filter(Tool.class::isInstance).map(Tool.class::cast)
                    .filter(tool -> "todo_create".equals(tool.getCard().getName())).findFirst().orElseThrow();
            ToolOutput output = (ToolOutput) create.invoke(Map.of("session_id", "file-session",
                    "tasks", List.of("existing file task")));
            assertThat(output.isSuccess()).isTrue();
            new JiuwenCoreAgentHandler(agent).prepareAgentForExecution(agent);
            assertThat(agent.getConfig().getTodoStorageType()).isEqualTo("file");
            assertThat(agent.getConfig().isTodoStorageTypeExplicit()).isFalse();
            assertThat(agent.getRegisteredTools()).containsExactlyElementsOf(tools);
            Tool list = tools.stream().filter(Tool.class::isInstance).map(Tool.class::cast)
                    .filter(tool -> "todo_list".equals(tool.getCard().getName())).findFirst().orElseThrow();
            ToolOutput loaded = (ToolOutput) list.invoke(Map.of("session_id", "file-session"));
            assertThat(loaded.isSuccess()).isTrue();
            assertThat(loaded.getData().toString()).contains("existing file task");
        }
    }

    @Test
    void doesNotAdaptOtherAgentsOrNonRedisRuntime() {
        DeepAgentConfig config = new DeepAgentConfig();
        DeepAgent agent = agent(config);
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(Map.of("type", "in_memory"));
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agent);
        handler.prepareAgentForExecution(agent);
        handler.prepareAgentForExecution("registered-agent");
        handler.prepareAgentForExecution(new Object());
        assertThat(config.isTodoStorageTypeExplicit()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adaptsActualInstanceBeforeSyncAndStreamingRunnerCalls(boolean streaming) {
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(Map.of("type", "redis"));
        DeepAgentConfig config = new DeepAgentConfig();
        DeepAgent agent = agent(config);
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(agent);
        Map<String, Object> inputs = Map.of("query", "test");
        try (var runner = mockStatic(Runner.class)) {
            if (streaming) {
                List<StreamMode> modes = List.of(StreamMode.OUTPUT);
                runner.when(() -> Runner.runAgentStreaming(agent, inputs, "session", null, modes))
                        .thenAnswer(call -> {
                            assertThat(config.getTodoStorageType()).isEqualTo("checkpointer_redis");
                            return Collections.emptyIterator();
                        });
                handler.executeAgentStreaming(inputs, "session", modes);
                runner.verify(() -> Runner.runAgentStreaming(agent, inputs, "session", null, modes));
            } else {
                runner.when(() -> Runner.runAgent(agent, inputs, "session", null)).thenAnswer(call -> {
                    assertThat(config.getTodoStorageType()).isEqualTo("checkpointer_redis");
                    return Map.of();
                });
                handler.executeAgent(inputs, "session");
                runner.verify(() -> Runner.runAgent(agent, inputs, "session", null));
            }
        }
    }
}
