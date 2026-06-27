/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.a2a;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.core.singleagent.agents.ReActAgentConfig;
import com.openjiuwen.core.singleagent.schema.AgentCard;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.spec.spi.AgentHandler;
import java.util.List;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Agent B: a real ReAct LLM agent that serves as a remote agent for Agent A.
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
@EnableConfigurationProperties(AgentBLlmProperties.class)
public class AgentBApp {
    private static final String AGENT_ID = "a2a-agent-b";

    public static void main(String[] args) {
        SpringApplication.run(AgentBApp.class, args);
    }

    @Bean
    AgentHandler agentBHandler(AgentBLlmProperties props) {
        props.requireConfigured();
        return new JiuwenCoreAgentHandler(buildReActAgent(props));
    }

    private static ReActAgent buildReActAgent(AgentBLlmProperties props) {
        AgentCard card = AgentCard.builder().id(AGENT_ID).name("AgentB")
                .description("A2A Agent B — real ReAct LLM agent").build();

        ReActAgent agent = new ReActAgent(card);
        ReActAgentConfig config = ReActAgentConfig.builder().maxIterations(props.getContextWindowLimit())
                .promptTemplate(List.of(Map.of("role", "system", "content", props.getSystemPrompt()))).build()
                .configureModelClient(props.getProvider(), props.getApiKey(), props.getApiBase(), props.getModelName(),
                        props.isSslVerify());
        // Override agent-core defaults (temperature=0.95, topP=0.1) with configured
        // values
        config.getModelConfigObj().setTemperature(props.getTemperature());
        config.getModelConfigObj().setTopP(props.getTopP());
        agent.configure(config);
        agent.registerRail(new CalcRail());
        return agent;
    }
}
