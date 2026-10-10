/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.runbudget.RunBudgetConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.app.config.runbudget.RunBudgetWiring;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        return build(agentId, name, description, config, null);
    }

    /**
     * Builds a configured {@link ReActAgent}, optionally wiring the FEAT-057 run budget.
     *
     * <p>When {@code wiring} is present and either budget dimension is enabled, a
     * {@code RunBudgetRail} is registered on the agent; an enabled turn dimension also raises
     * the loop envelope to {@code hardLimitMax + 1} as the rail-independent fallback. Without
     * wiring the behavior is byte-identical to the legacy path.</p>
     *
     * @param agentId the agent identifier
     * @param name the agent display name
     * @param description the agent description
     * @param config the resolved LLM configuration
     * @param wiring run-budget wiring inputs, may be {@code null}
     * @return the configured ReAct agent
     */
    public static ReActAgent build(String agentId, String name, String description, ResolvedLlmConfig config,
        RunBudgetWiring wiring) {
        Optional<RunBudgetConfig> budgetConfig = RunBudgetWirer.resolveBudgetConfig(wiring);
        int maxIterations = config.getMaxIterations();
        if (budgetConfig.isPresent() && budgetConfig.get().isTurnEnabled()) {
            maxIterations = RunBudgetWirer.fallbackMaxIterations(budgetConfig.get());
        }
        ReActAgentConfig agentConfig = ReActAgentConfig.builder()
            .promptTemplate(List.of(Map.of("role", "system", "content", config.getSystemPrompt())))
            .maxIterations(maxIterations)
            .build()
            .configureModelClient(config.getProvider(), config.getApiKey(), config.getApiBase(), config.getModelName(),
                config.isSslVerify())
            .configureContextEngine(null, config.getContextWindowLimit(), false, false);
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
        AgentCard card = AgentCard.builder().id(agentId).name(name).description(description).build();
        ReActAgent agent = new ReActAgent(card);
        agent.configure(agentConfig);
        budgetConfig.ifPresent(resolved -> RunBudgetWirer.registerRail(agent, resolved));
        return agent;
    }
}
