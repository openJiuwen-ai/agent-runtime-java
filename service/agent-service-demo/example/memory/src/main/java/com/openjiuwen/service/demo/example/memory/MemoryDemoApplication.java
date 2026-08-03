/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.memory;

import com.openjiuwen.core.memory.external.MemoryProvider;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.memory.MemoryStoreMemoryProvider;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;
import com.openjiuwen.service.demo.example.support.ExampleReActAgentFactory;
import com.openjiuwen.service.demo.example.support.MemoryToolRegistrar;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Memory external service feature demo — independent runnable module.
 *
 * <p>Fill in {@code openjiuwen.service.llm} and {@code openjiuwen.service.middleware.memory} then start;
 * the ReAct agent prefetches memory before each request, syncs the turn afterwards, and exposes
 * memory tools backed by a governed {@link MemoryStore}.
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
@EnableConfigurationProperties(MiddlewareProperties.class)
public class MemoryDemoApplication {
    private static final String AGENT_ID = "demo-memory-agent";

    private static final String MEMORY_SYSTEM_PROMPT_SUFFIX =
        "\n\n# Long-term Memory\nBefore each request, the service may include a <memory-context> block."
            + "\nUse it as factual context for this user."
            + "\nYou may also call memory_search to find memories and memory_add to store durable facts."
            + "\nUse memory_get to fetch a memory by id (from search results)."
            + " Use memory_delete only when the user explicitly asks to remove a memory.";

    public static void main(String[] args) {
        SpringApplication.run(MemoryDemoApplication.class, args);
    }

    @Bean
    AgentHandler agentHandler(LlmConfigResolver llmConfigResolver,
        ObjectProvider<MemoryStore> memoryStoreProvider,
        ObjectProvider<MemoryProvider> memoryProviderProvider,
        ObjectProvider<ExternalSvcAdapterRegistrar> externalSvcAdapterRegistrarProvider,
        ObjectProvider<MiddlewareProperties> middlewarePropertiesProvider) {
        MemoryStore memoryStore = memoryStoreProvider.getIfAvailable();
        ResolvedLlmConfig resolvedLlmConfig = llmConfigResolver.resolveRequired();
        ResolvedLlmConfig agentLlmConfig = memoryStore == null
            ? resolvedLlmConfig
            : withMemorySystemPrompt(resolvedLlmConfig);
        boolean shouldUseRequestScopedSession = middlewarePropertiesProvider
            .getIfAvailable(MiddlewareProperties::new)
            .getMemory()
            .isRequestScopedSession();

        ReActAgent agent = ExampleReActAgentFactory.build(AGENT_ID, "Demo Memory Agent",
            "ReAct agent with governed MemoryStore tools", agentLlmConfig);
        if (memoryStore != null) {
            MemoryToolRegistrar.register(agent, memoryStore, true);
        }
        AgentHandler coreHandler = new MemoryAwareJiuwenCoreAgentHandler(agent,
            externalSvcAdapterRegistrarProvider.getIfAvailable(ExternalSvcAdapterRegistrar::noop),
            shouldUseRequestScopedSession);
        MemoryProvider memoryProvider = memoryProviderProvider.getIfAvailable();
        return memoryProvider instanceof MemoryStoreMemoryProvider runtimeMemoryProvider
            ? new MemoryLifecycleAgentHandler(coreHandler, runtimeMemoryProvider)
            : coreHandler;
    }

    private static ResolvedLlmConfig withMemorySystemPrompt(ResolvedLlmConfig config) {
        return ResolvedLlmConfig.builder()
            .provider(config.getProvider())
            .apiKey(config.getApiKey())
            .apiBase(config.getApiBase())
            .modelName(config.getModelName())
            .sslVerify(config.isSslVerify())
            .systemPrompt(config.getSystemPrompt() + MEMORY_SYSTEM_PROMPT_SUFFIX)
            .temperature(config.getTemperature())
            .topP(config.getTopP())
            .timeout(config.getTimeout())
            .contextWindowLimit(config.getContextWindowLimit())
            .maxIterations(config.getMaxIterations())
            .build();
    }
}
