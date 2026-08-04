/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

/**
 * Unit tests for {@link ExampleDeepAgentFactory}.
 */
class ExampleDeepAgentFactoryTest {
    @Test
    void build_appliesCardPromptAndModelSettings() {
        ResolvedLlmConfig config = config();

        DeepAgent agent = ExampleDeepAgentFactory.build("agent-c", "Agent C", "DeepAgent demo", config);
        Object rawModel = agent.getConfig().getModel();
        if (!(rawModel instanceof Model model)) {
            throw new AssertionError("DeepAgent must expose a Model");
        }

        assertThat(agent.getCard().getId()).isEqualTo("agent-c");
        assertThat(agent.getCard().getName()).isEqualTo("Agent C");
        assertThat(agent.getCard().getDescription()).isEqualTo("DeepAgent demo");
        assertThat(agent.getConfig().getSystemPrompt()).isEqualTo("Use deep_calc before answering.");
        assertThat(agent.getConfig().getMaxIterations()).isEqualTo(3);
        assertThat(agent.getConfig().isEnableTaskLoop()).isTrue();
        assertThat(model.getModelClientConfig().getClientProvider()).isEqualTo("OpenAI");
        assertThat(model.getModelClientConfig().getApiKey()).isEqualTo("test-key");
        assertThat(model.getModelClientConfig().getApiBase()).isEqualTo("http://localhost/v1");
        assertThat(model.getModelClientConfig().getTimeout()).isEqualTo(60.0D);
        assertThat(model.getModelClientConfig().isVerifySsl()).isTrue();
        assertThat(model.getModelConfig().getModelName()).isEqualTo("test-model");
        assertThat(model.getModelConfig().getTemperature()).isEqualTo(0.6D);
        assertThat(model.getModelConfig().getTopP()).isEqualTo(0.8D);
    }

    @Test
    void build_registersRailsInDeepAgentConfig() {
        ResolvedLlmConfig config = config();
        Object rail = new Object();

        DeepAgent agent = ExampleDeepAgentFactory.build("agent-c", "Agent C", "DeepAgent demo", config, List.of(rail));

        assertThat(agent.getConfig().getRails()).contains(rail);
    }

    @Test
    void build_derivesWorkspacePathFromAgentId() {
        ResolvedLlmConfig config = config();

        DeepAgent agent = ExampleDeepAgentFactory.build("agent-d", "Agent D", "DeepAgent demo", config);

        assertThat(agent.getConfig().getWorkspacePath().replace('\\', '/'))
            .endsWith("target/deepagents/agent-d");
    }

    private static ResolvedLlmConfig config() {
        return ResolvedLlmConfig.builder()
            .provider("OpenAI")
            .apiKey("test-key")
            .apiBase("http://localhost/v1")
            .modelName("test-model")
            .sslVerify(true)
            .systemPrompt("Use deep_calc before answering.")
            .temperature(0.6D)
            .topP(0.8D)
            .timeout(Duration.ofSeconds(60))
            .contextWindowLimit(10)
            .maxIterations(3)
            .build();
    }
}
