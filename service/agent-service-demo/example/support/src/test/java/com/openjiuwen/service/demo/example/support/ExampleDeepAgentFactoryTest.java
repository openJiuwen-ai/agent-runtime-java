/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link ExampleDeepAgentFactory}.
 */
class ExampleDeepAgentFactoryTest {
    @Test
    void buildCreatesDeepAgentWithConfiguredCardAndPrompt() {
        ResolvedLlmConfig config = config();

        DeepAgent agent = ExampleDeepAgentFactory.build("agent-c", "Agent C", "DeepAgent demo", config);

        assertThat(agent.getCard().getId()).isEqualTo("agent-c");
        assertThat(agent.getCard().getName()).isEqualTo("Agent C");
        assertThat(agent.getCard().getDescription()).isEqualTo("DeepAgent demo");
        assertThat(agent.getConfig().getSystemPrompt()).isEqualTo("Use deep_calc before answering.");
        assertThat(agent.getConfig().getMaxIterations()).isEqualTo(3);
        assertThat(agent.getConfig().isEnableTaskLoop()).isTrue();
    }

    @Test
    void buildCanRegisterRailsInDeepAgentConfig() {
        ResolvedLlmConfig config = config();
        Object rail = new Object();

        DeepAgent agent = ExampleDeepAgentFactory.build("agent-c", "Agent C", "DeepAgent demo", config, List.of(rail));

        assertThat(agent.getConfig().getRails()).contains(rail);
    }

    @Test
    void buildDerivesWorkspacePathFromAgentId() {
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
            .timeout(java.time.Duration.ofSeconds(60))
            .contextWindowLimit(10)
            .maxIterations(3)
            .build();
    }
}
