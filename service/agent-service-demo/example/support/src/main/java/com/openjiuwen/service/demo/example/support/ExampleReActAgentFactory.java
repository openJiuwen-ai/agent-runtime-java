/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;

import java.util.List;
import java.util.Map;

/**
 * Builds a {@link ReActAgent} from {@link ExampleLlmProperties}.
 *
 * @since 0.1.0
 */
public final class ExampleReActAgentFactory {

    private ExampleReActAgentFactory() {
    }

    public static ReActAgent build(String agentId, String name, String description, ExampleLlmProperties props) {
        AgentCard card = AgentCard.builder().id(agentId).name(name).description(description).build();
        ReActAgent agent = new ReActAgent(card);
        ReActAgentConfig config = ReActAgentConfig.builder()
                .maxIterations(props.getContextWindowLimit())
                .promptTemplate(List.of(Map.of("role", "system", "content", props.getSystemPrompt())))
                .build()
                .configureModelClient(props.getProvider(), props.getApiKey(), props.getApiBase(),
                        props.getModelName(), props.isSslVerify());
        config.getModelConfigObj().setTemperature(props.getTemperature());
        config.getModelConfigObj().setTopP(props.getTopP());
        agent.configure(config);
        return agent;
    }
}
