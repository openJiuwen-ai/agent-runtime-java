/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.DefaultModelClientFactories;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.harness.factory.HarnessFactory;
import com.openjiuwen.harness.schema.config.DeepAgentConfig;
import com.openjiuwen.harness.workspace.Workspace;

import java.util.List;
import java.util.Map;

/**
 * Builds a {@link DeepAgent} from {@link DemoLlmProperties}.
 *
 * @since 0.1.0
 */
public final class ExampleDeepAgentFactory {
    private static final String LANGUAGE = "zh-CN";

    private static final String WORKSPACE_ROOT = "target/deepagents";

    private ExampleDeepAgentFactory() {
    }

    /**
     * Builds a DeepAgent without custom rails.
     *
     * @param agentId
     *            the agent id
     * @param name
     *            the agent display name
     * @param description
     *            the agent description
     * @param props
     *            the LLM and prompt properties
     * @return a configured DeepAgent
     */
    public static DeepAgent build(String agentId, String name, String description, DemoLlmProperties props) {
        return build(agentId, name, description, props, List.of());
    }

    /**
     * Builds a DeepAgent with optional custom rails.
     *
     * @param agentId
     *            the agent id
     * @param name
     *            the agent display name
     * @param description
     *            the agent description
     * @param props
     *            the LLM and prompt properties
     * @param rails
     *            the rails to register on the agent
     * @return a configured DeepAgent
     */
    public static DeepAgent build(String agentId, String name, String description, DemoLlmProperties props,
        List<Object> rails) {
        String workspacePath = WORKSPACE_ROOT + "/" + agentId;
        DeepAgentConfig config = DeepAgentConfig.builder()
            .systemPrompt(props.getSystemPrompt())
            .maxIterations(props.getMaxIterations())
            .language(LANGUAGE)
            .workspacePath(workspacePath)
            .rails(rails)
            .model(buildModel(props))
            .restrictToWorkDir(true)
            .enableTaskLoop(true)
            .enableTaskPlanning(false)
            .addGeneralPurposeAgent(false)
            .build();
        AgentCard card = AgentCard.builder().id(agentId).name(name).description(description).build();
        Workspace workspace = Workspace.builder().rootPath(workspacePath).language(LANGUAGE).links(Map.of()).build();
        return HarnessFactory.createDeepAgent(card, config, workspace);
    }

    private static Model buildModel(DemoLlmProperties props) {
        DefaultModelClientFactories.ensureRegistered();
        ModelClientConfig clientConfig = ModelClientConfig.builder()
            .clientProvider(props.getProvider())
            .apiKey(props.getApiKey())
            .apiBase(props.getApiBase())
            .timeout(props.getTimeout().toSeconds())
            .verifySsl(props.isSslVerify())
            .build();
        ModelRequestConfig requestConfig = ModelRequestConfig.builder()
            .modelName(props.getModelName())
            .temperature(props.getTemperature())
            .topP(props.getTopP())
            .build();
        return new Model(clientConfig, requestConfig);
    }
}
