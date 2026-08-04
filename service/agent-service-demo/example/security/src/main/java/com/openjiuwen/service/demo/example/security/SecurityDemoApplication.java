/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.security;

import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.demo.example.support.ExampleReActAgentFactory;
import com.openjiuwen.service.spec.security.FineGrainedAuthorizer;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * Ingress TLS / fine-grained authorization feature demo — independent runnable module.
 *
 * <p>Default profile enables {@code openjiuwen.service.security.auth} on HTTP port 8095.
 * See {@code application-security.example.yml} for full configuration reference.
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
public class SecurityDemoApplication {
    private static final String AGENT_ID = "demo-security-agent";

    public static void main(String[] args) {
        SpringApplication.run(SecurityDemoApplication.class, args);
    }

    /**
     * Demo authorizer bean required when {@code auth.enabled=true}.
     *
     * @return fine-grained authorizer
     */
    @Bean
    FineGrainedAuthorizer fineGrainedAuthorizer() {
        return new DemoFineGrainedAuthorizer();
    }

    @Bean
    AgentHandler agentHandler(LlmConfigResolver llmConfigResolver,
        ObjectProvider<ExternalSvcAdapterRegistrar> externalSvcAdapterRegistrarProvider) {
        ResolvedLlmConfig llmConfig = llmConfigResolver.resolveRequired();
        ReActAgent agent = ExampleReActAgentFactory.build(AGENT_ID, "Demo Security Agent",
            "ReAct agent for ingress security demo", llmConfig);
        return new JiuwenCoreAgentHandler(agent,
            externalSvcAdapterRegistrarProvider.getIfAvailable(ExternalSvcAdapterRegistrar::noop));
    }
}
