/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.harness.deep_agent.DeepAgent;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Unit tests for {@link ExampleDeepAgentFactory}.
 */
class ExampleDeepAgentFactoryTest {
    @Test
    void buildCreatesDeepAgentWithConfiguredCardAndPrompt() {
        DemoLlmProperties props = props();

        DeepAgent agent = ExampleDeepAgentFactory.build("agent-c", "Agent C", "DeepAgent demo", props);

        assertThat(agent.getCard().getId()).isEqualTo("agent-c");
        assertThat(agent.getCard().getName()).isEqualTo("Agent C");
        assertThat(agent.getCard().getDescription()).isEqualTo("DeepAgent demo");
        assertThat(agent.getConfig().getSystemPrompt()).isEqualTo("Use deep_calc before answering.");
        assertThat(agent.getConfig().getMaxIterations()).isEqualTo(3);
        assertThat(agent.getConfig().isEnableTaskLoop()).isTrue();
    }

    @Test
    void buildCanRegisterRailsInDeepAgentConfig() {
        DemoLlmProperties props = props();
        Object rail = new Object();

        DeepAgent agent = ExampleDeepAgentFactory.build("agent-c", "Agent C", "DeepAgent demo", props, List.of(rail));

        assertThat(agent.getConfig().getRails()).contains(rail);
    }

    @Test
    void buildDerivesWorkspacePathFromAgentId() {
        DemoLlmProperties props = props();

        DeepAgent agent = ExampleDeepAgentFactory.build("agent-d", "Agent D", "DeepAgent demo", props);

        assertThat(agent.getConfig().getWorkspacePath().replace('\\', '/'))
            .endsWith("target/deepagents/agent-d");
    }

    private static DemoLlmProperties props() {
        DemoLlmProperties props = new DemoLlmProperties();
        props.setApiKey("test-key");
        props.setApiBase("http://localhost/v1");
        props.setModelName("test-model");
        props.setSystemPrompt("Use deep_calc before answering.");
        props.setMaxIterations(3);
        return props;
    }
}
