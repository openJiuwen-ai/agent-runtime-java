/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency;

import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.demo.example.concurrency.mock.ConcurrencyDemoLlmSupport;
import com.openjiuwen.service.demo.example.support.ExampleDeepAgentFactory;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * DeepAgent + Redis checkpoint + A2A skills concurrency validation demo.
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = {
    "com.openjiuwen.service.app",
    "com.openjiuwen.service.demo.example.concurrency"
})
public class ConcurrencyDemoApplication {
    private static final String AGENT_ID = "demo-concurrency-agent";

    private static final Logger LOG = LoggerFactory.getLogger(ConcurrencyDemoApplication.class);

    static final String ENABLE_TASK_LOOP_PROPERTY = "demo.concurrency.enable-task-loop";

    public static void main(String[] args) {
        SpringApplication.run(ConcurrencyDemoApplication.class, args);
    }

    static boolean resolveEnableTaskLoop() {
        String raw = System.getProperty(ENABLE_TASK_LOOP_PROPERTY);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("DEMO_CONCURRENCY_ENABLE_TASK_LOOP");
        }
        return raw == null || raw.isBlank() || Boolean.parseBoolean(raw.trim());
    }

    @Bean
    AgentHandler agentHandler(LlmConfigResolver llmConfigResolver, ConcurrencyDemoLlmSupport llmSupport,
        ObjectProvider<ExternalSvcAdapterRegistrar> externalSvcAdapterRegistrarProvider) {
        ResolvedLlmConfig llmConfig = llmSupport.resolveForAgent(llmConfigResolver);
        boolean enableTaskLoop = resolveEnableTaskLoop();
        LOG.info("Concurrency demo DeepAgent enableTaskLoop={}", enableTaskLoop);
        DeepAgent agent = ExampleDeepAgentFactory.build(AGENT_ID, "Concurrency Demo DeepAgent",
            "DeepAgent for multi-session concurrent load validation with skill-like tools and Redis checkpoint",
            llmConfig, List.of(new SkillEchoRail(), new ConcurrentLookupRail()), enableTaskLoop);
        return new JiuwenCoreAgentHandler(agent,
            externalSvcAdapterRegistrarProvider.getIfAvailable(ExternalSvcAdapterRegistrar::noop));
    }
}
