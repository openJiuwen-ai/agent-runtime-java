/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.demo.example.support.DemoLlmProperties;
import com.openjiuwen.service.demo.example.support.ExampleReActAgentFactory;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Agent B (port 18091): a ReAct LLM agent that provides a calc tool with user-confirmation
 * interrupt, serving as a remote agent for Agent A via the A2A protocol.
 *
 * <p>Start this agent FIRST, before Agent A.</p>
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
@EnableConfigurationProperties(DemoLlmProperties.class)
public class A2aAgentBDemoApplication {
    private static final String AGENT_ID = "demo-a2a-agent-b";

    public static void main(String[] args) {
        new SpringApplicationBuilder(A2aAgentBDemoApplication.class)
                .properties("spring.config.import="
                        + "optional:classpath:application-base.yml,"
                        + "optional:classpath:application-base_local.yml,"
                        + "optional:classpath:application-a2a-agent-b.yml,"
                        + "optional:classpath:application-a2a-redis.local.yml")
                .run(args);
    }

    @Bean
    AgentHandler agentBHandler(DemoLlmProperties llmProperties) {
        llmProperties.applyApiConfigIfPresent();
        llmProperties.requireConfigured();
        ReActAgent agent = ExampleReActAgentFactory.build(AGENT_ID, "Agent B (A2A Demo)",
                "ReAct agent with calc tool for A2A demo", llmProperties);
        agent.registerRail(new CalcInterruptRail());
        return new JiuwenCoreAgentHandler(agent);
    }
}
