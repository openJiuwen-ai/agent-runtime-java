/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.demo.example.support.DemoLlmProperties;
import com.openjiuwen.service.demo.example.support.ExampleDeepAgentFactory;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Agent C (port 18092): a DeepAgent that triggers the final user confirmation
 * interrupt for the A -> B -> C demo path.
 *
 * <p>
 * Start this agent before Agent B and Agent A.
 * </p>
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
@EnableConfigurationProperties(DemoLlmProperties.class)
public class A2aAgentCDemoApplication {
    private static final String AGENT_ID = "demo-a2a-agent-c";

    public static void main(String[] args) {
        new SpringApplicationBuilder(A2aAgentCDemoApplication.class).properties("spring.config.import="
                + "optional:classpath:application-base.yml," + "optional:classpath:application-base_local.yml,"
                + "optional:classpath:application-a2a-agent-c.yml,"
                + "optional:classpath:application-a2a-redis.local.yml").run(args);
    }

    @Bean
    AgentHandler agentCHandler(DemoLlmProperties llmProperties) {
        llmProperties.applyApiConfigIfPresent();
        llmProperties.requireConfigured();
        DeepAgent agent = ExampleDeepAgentFactory.build(AGENT_ID, "Agent C (A2A Food Demo)",
                "DeepAgent with food recommendation confirmation tool for A2A demo", llmProperties,
                List.of(new FoodRecommendInterruptRail()));
        return new JiuwenCoreAgentHandler(agent);
    }
}
