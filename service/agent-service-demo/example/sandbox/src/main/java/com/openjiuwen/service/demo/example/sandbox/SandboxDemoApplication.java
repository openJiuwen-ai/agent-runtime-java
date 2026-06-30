/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.sandbox;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.demo.example.support.DemoLlmProperties;
import com.openjiuwen.service.demo.example.support.ExampleReActAgentFactory;
import com.openjiuwen.service.spec.spi.AgentHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Sandbox external service feature demo — independent runnable module.
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
@EnableConfigurationProperties(DemoLlmProperties.class)
public class SandboxDemoApplication {

    private static final String AGENT_ID = "demo-sandbox-agent";

    public static void main(String[] args) {
        SpringApplication.run(SandboxDemoApplication.class, args);
    }

    @Bean
    AgentHandler agentHandler(DemoLlmProperties llmProperties,
            ObjectProvider<ExternalSvcAdapterRegistrar> externalSvcAdapterRegistrarProvider) {
        llmProperties.applyApiConfigIfPresent();
        llmProperties.requireConfigured();
        ReActAgent agent = ExampleReActAgentFactory.build(AGENT_ID, "Demo Sandbox Agent",
                "ReAct agent with external Sandbox client", llmProperties);
        return new JiuwenCoreAgentHandler(agent,
                externalSvcAdapterRegistrarProvider.getIfAvailable(ExternalSvcAdapterRegistrar::noop));
    }
}
