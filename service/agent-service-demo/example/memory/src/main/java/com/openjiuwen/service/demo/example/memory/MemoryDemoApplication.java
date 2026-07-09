/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.memory;

import com.openjiuwen.core.memory.external.MemoryProvider;
import com.openjiuwen.core.singleagent.agents.ReActAgent;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.demo.example.support.DemoLlmProperties;
import com.openjiuwen.service.demo.example.support.ExampleReActAgentFactory;
import com.openjiuwen.service.demo.example.support.MemoryToolRegistrar;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Memory (mem0) external service feature demo — independent runnable module.
 *
 * <p>Fill in {@code openjiuwen.demo.llm} and {@code openjiuwen.service.middleware.memory} then start;
 * the ReAct agent prefetches memory before each request, syncs the turn afterwards, and exposes
 * mem0 tools backed by a governed {@link MemoryStore}.
 *
 * @since 0.1.0
 */
@SpringBootApplication(scanBasePackages = "com.openjiuwen.service.app")
@EnableConfigurationProperties({DemoLlmProperties.class, MiddlewareProperties.class})
public class MemoryDemoApplication {
    private static final String AGENT_ID = "demo-memory-agent";

    public static void main(String[] args) {
        SpringApplication.run(MemoryDemoApplication.class, args);
    }

    @Bean
    AgentHandler agentHandler(DemoLlmProperties llmProperties,
        ObjectProvider<MemoryStore> memoryStoreProvider,
        ObjectProvider<MemoryProvider> memoryProviderProvider,
        ObjectProvider<ExternalSvcAdapterRegistrar> externalSvcAdapterRegistrarProvider,
        ObjectProvider<MiddlewareProperties> middlewarePropertiesProvider) {
        llmProperties.applyApiConfigIfPresent();
        llmProperties.requireConfigured();
        augmentSystemPromptWithMemory(llmProperties, memoryStoreProvider);
        appendManagementToolPromptIfMemoryEnabled(memoryStoreProvider, llmProperties);
        boolean requestScopedSession = middlewarePropertiesProvider
            .getIfAvailable(MiddlewareProperties::new)
            .getMemory()
            .isRequestScopedSession();

        ReActAgent agent = ExampleReActAgentFactory.build(AGENT_ID, "Demo Memory Agent",
            "ReAct agent with governed MemoryStore tools", llmProperties);
        memoryStoreProvider.ifAvailable(memoryStore -> MemoryToolRegistrar.register(agent, memoryStore, true));
        AgentHandler coreHandler = new MemoryAwareJiuwenCoreAgentHandler(agent,
            externalSvcAdapterRegistrarProvider.getIfAvailable(ExternalSvcAdapterRegistrar::noop),
            requestScopedSession);
        MemoryProvider memoryProvider = memoryProviderProvider.getIfAvailable();
        return memoryProvider != null
            ? new MemoryLifecycleAgentHandler(coreHandler, memoryProvider)
            : coreHandler;
    }

    private static void augmentSystemPromptWithMemory(DemoLlmProperties llmProperties,
        ObjectProvider<MemoryStore> memoryStoreProvider) {
        memoryStoreProvider.ifAvailable(ignored -> llmProperties.setSystemPrompt(llmProperties.getSystemPrompt()
            + "\n\n# Long-term Memory\nBefore each request, the service may include a <memory-context> block."
            + "\nUse it as factual context for this user."
            + "\nYou may also call memory_search to find memories and memory_add to store durable facts."));
    }

    private static void appendManagementToolPromptIfMemoryEnabled(
        ObjectProvider<MemoryStore> memoryStoreProvider, DemoLlmProperties llmProperties) {
        memoryStoreProvider.ifAvailable(ignored -> appendManagementToolPrompt(llmProperties));
    }

    private static void appendManagementToolPrompt(DemoLlmProperties llmProperties) {
        llmProperties.setSystemPrompt(llmProperties.getSystemPrompt()
            + "\nUse memory_get to fetch a memory by id (from search results)."
            + " Use memory_delete only when the user explicitly asks to remove a memory.");
    }
}
