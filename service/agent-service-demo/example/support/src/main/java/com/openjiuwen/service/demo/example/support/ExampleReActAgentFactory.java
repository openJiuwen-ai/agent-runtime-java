/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ReActAgent} from {@link DemoLlmProperties}.
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
     * @param props the demo LLM properties
     * @return the configured ReAct agent
     */
    public static ReActAgent build(String agentId, String name, String description, DemoLlmProperties props) {
        AgentCard card = AgentCard.builder().id(agentId).name(name).description(description).build();
        ReActAgent agent = new ReActAgent(card);
        ReActAgentConfig config = ReActAgentConfig.builder()
                .promptTemplate(List.of(Map.of("role", "system", "content", props.getSystemPrompt())))
                .maxIterations(props.getMaxIterations()).build().configureModelClient(props.getProvider(),
                        props.getApiKey(), props.getApiBase(), props.getModelName(), props.isSslVerify())
                .configureContextEngine(null, props.getContextWindowLimit(), false);
        ModelRequestConfig requestConfig = config.getModelConfigObj();
        requestConfig.setTemperature(props.getTemperature());
        requestConfig.setTopP(props.getTopP());
        agent.configure(config);
        return agent;
    }
}
