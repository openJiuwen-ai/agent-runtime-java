/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.a2a;

import com.openjiuwen.core.application.schema.WorkflowAgentConfig;
import com.openjiuwen.core.application.workflow.WorkflowAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Agent D (port 18093): an expense WorkflowAgent with manual approval and an LLM final report.
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
public class A2aAgentDDemoApplication {
    private static final String AGENT_ID = "demo-a2a-agent-d";

    public static void main(String[] args) {
        new SpringApplicationBuilder(A2aAgentDDemoApplication.class).properties("spring.config.import="
                + "optional:classpath:application-base.yml," + "optional:classpath:application-base_local.yml,"
                + "optional:classpath:application-a2a-agent-d.yml,"
                + "optional:classpath:application-a2a-redis.local.yml").run(args);
    }

    @Bean
    AgentHandler agentDHandler(LlmConfigResolver llmConfigResolver) {
        ResolvedLlmConfig llmConfig = llmConfigResolver.resolveRequired();
        WorkflowAgentConfig config = WorkflowAgentConfig.builder().id(AGENT_ID)
                .description("Agent D expense compliance and approval workflow").build();
        WorkflowAgent agent = new WorkflowAgent(config);
        agent.addWorkflows(List.of(ExpenseReviewWorkflow.build(llmConfig)));
        return new JiuwenCoreAgentHandler(agent);
    }
}
