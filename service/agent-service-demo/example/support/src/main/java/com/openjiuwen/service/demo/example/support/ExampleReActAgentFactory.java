/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;

import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ReActAgent} from reusable service LLM configuration.
 *
 * @since 0.1.0
 */
public final class ExampleReActAgentFactory {
    private ExampleReActAgentFactory() {
    }

    /**
     * Builds a configured {@link ReActAgent} for demo and example modules.
     *
     * @param agentId the agent identifier
     * @param name the agent display name
     * @param description the agent description
     * @param config the resolved LLM configuration
     * @return the configured ReAct agent
     */
    public static ReActAgent build(String agentId, String name, String description, ResolvedLlmConfig config) {
        AgentCard card = AgentCard.builder().id(agentId).name(name).description(description).build();
        ReActAgentConfig agentConfig = ReActAgentConfig.builder()
            .promptTemplate(List.of(Map.of("role", "system", "content", config.getSystemPrompt())))
            .maxIterations(config.getMaxIterations())
            .build()
            .configureModelClient(config.getProvider(), config.getApiKey(), config.getApiBase(), config.getModelName(),
                config.isSslVerify())
            .configureContextEngine(null, config.getContextWindowLimit(), false);
        ModelClientConfig currentClientConfig = agentConfig.getModelClientConfig();
        agentConfig.setModelClientConfig(ModelClientConfig.builder()
            .clientId(currentClientConfig.getClientId())
            .clientProvider(currentClientConfig.getClientProvider())
            .apiKey(currentClientConfig.getApiKey())
            .apiBase(currentClientConfig.getApiBase())
            .timeout(config.getTimeout().toMillis() / 1000.0D)
            .maxRetries(currentClientConfig.getMaxRetries())
            .verifySsl(currentClientConfig.isVerifySsl())
            .sslCert(currentClientConfig.getSslCert())
            .headers(currentClientConfig.getHeaders())
            .build());
        ModelRequestConfig requestConfig = agentConfig.getModelConfigObj();
        requestConfig.setTemperature(config.getTemperature());
        requestConfig.setTopP(config.getTopP());
        ReActAgent agent = new ReActAgent(card);
        agent.configure(agentConfig);
        return agent;
    }
}
