/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Unit tests for {@link ExampleReActAgentFactory}.
 */
class ExampleReActAgentFactoryTest {
    @Test
    void build_appliesConnectionAndRequestSettings() {
        ResolvedLlmConfig config = ResolvedLlmConfig.builder()
            .provider("OpenAI")
            .apiKey("test-key")
            .apiBase("https://localhost/v1")
            .modelName("test-model")
            .sslVerify(false)
            .systemPrompt("Test prompt")
            .temperature(0.2D)
            .topP(0.7D)
            .timeout(Duration.ofMillis(1500))
            .contextWindowLimit(12)
            .maxIterations(4)
            .build();

        ReActAgent agent = ExampleReActAgentFactory.build("agent", "Agent", "description", config);
        Object rawConfig = agent.getConfig();
        if (!(rawConfig instanceof ReActAgentConfig agentConfig)) {
            throw new AssertionError("ReActAgent must expose a ReActAgentConfig");
        }

        assertThat(agentConfig.getModelClientConfig().getTimeout()).isEqualTo(1.5D);
        assertThat(agentConfig.getModelConfigObj().getTemperature()).isEqualTo(0.2D);
        assertThat(agentConfig.getModelConfigObj().getTopP()).isEqualTo(0.7D);
        assertThat(agentConfig.getMaxIterations()).isEqualTo(4);
    }
}
