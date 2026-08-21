/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.deepagent;

import com.openjiuwen.harness.deep_agent.DeepAgent;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.demo.example.support.ExampleDeepAgentFactory;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * Minimal DeepAgent HTTP demo for multi-turn context validation.
 * <p>
 * Unlike {@code DemoAgentApplication} (8090, ReActAgent) and
 * {@code ConcurrencyDemoApplication} (8096, DeepAgent + skills/load test),
 * this module exposes a plain DeepAgent with task-loop and in-memory checkpoint.
 * </p>
 *
 * <pre>
 * mvn -pl agent-service-demo/example/deepagent -am spring-boot:run
 * curl http://127.0.0.1:8097/health
 * </pre>
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
public class DeepAgentDemoApplication {
    private static final String AGENT_ID = "demo-deepagent";

    private static final Logger log = LoggerFactory.getLogger(DeepAgentDemoApplication.class);

    /**
     * Starts the DeepAgent demo Spring Boot application.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        SpringApplication.run(DeepAgentDemoApplication.class, args);
    }

    /**
     * Registers a minimal DeepAgent as the service handler.
     *
     * @param llmConfigResolver LLM configuration resolver
     * @param externalSvcAdapterRegistrarProvider optional external service adapters
     * @return configured agent handler
     */
    @Bean
    AgentHandler agentHandler(LlmConfigResolver llmConfigResolver,
            ObjectProvider<ExternalSvcAdapterRegistrar> externalSvcAdapterRegistrarProvider) {
        ResolvedLlmConfig llmConfig = llmConfigResolver.resolveRequired();
        log.info("Starting minimal DeepAgent demo (taskLoop=true, checkpointer=in_memory)");
        DeepAgent agent = ExampleDeepAgentFactory.build(AGENT_ID, "DeepAgent Demo",
                "Minimal DeepAgent for multi-turn context validation via /v1/query",
                llmConfig, List.of(), true);
        return new JiuwenCoreAgentHandler(agent,
                externalSvcAdapterRegistrarProvider.getIfAvailable(ExternalSvcAdapterRegistrar::noop));
    }
}
