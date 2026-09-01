/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.redis;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.demo.example.support.ExampleReActAgentFactory;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Redis Checkpointer feature demo — independent runnable module.
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
public class RedisDemoApplication {
    private static final String AGENT_ID = "demo-redis-agent";

    public static void main(String[] args) {
        SpringApplication.run(RedisDemoApplication.class, args);
    }

    @Bean
    AgentHandler agentHandler(LlmConfigResolver llmConfigResolver,
        ObjectProvider<ExternalSvcAdapterRegistrar> externalSvcAdapterRegistrarProvider) {
        ResolvedLlmConfig llmConfig = llmConfigResolver.resolveRequired();
        ReActAgent agent = ExampleReActAgentFactory.build(AGENT_ID, "Demo Redis Agent",
            "ReAct agent for Redis Checkpointer demo", llmConfig);
        return new JiuwenCoreAgentHandler(agent,
            externalSvcAdapterRegistrarProvider.getIfAvailable(ExternalSvcAdapterRegistrar::noop));
    }
}
