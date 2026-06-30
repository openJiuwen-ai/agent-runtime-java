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
 * Agent A (port 18090): a ReAct LLM agent that discovers Agent B as a remote A2A agent
 * and delegates computation tasks via the delegate_to_agentb tool.
 *
 * <p>Start this agent AFTER Agent B is already running on port 18091.</p>
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
@EnableConfigurationProperties(DemoLlmProperties.class)
public class A2aAgentADemoApplication {

    private static final String AGENT_ID = "demo-a2a-agent-a";

    public static void main(String[] args) {
        new SpringApplicationBuilder(A2aAgentADemoApplication.class)
                .properties("spring.config.import="
                        + "optional:classpath:application-base.yml,"
                        + "optional:classpath:application-base_local.yml,"
                        + "optional:classpath:application-a2a-agent-a.yml,"
                        + "optional:classpath:application-a2a-redis.yml")
                .run(args);
    }

    @Bean
    AgentHandler agentAHandler(DemoLlmProperties llmProperties) {
        llmProperties.applyApiConfigIfPresent();
        llmProperties.requireConfigured();
        ReActAgent agent = ExampleReActAgentFactory.build(AGENT_ID, "Agent A (A2A Demo)",
                "ReAct agent that delegates to Agent B via A2A", llmProperties);
        agent.registerRail(new A2aDelegateRail());
        return new JiuwenCoreAgentHandler(agent);
    }
}
