/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.memory.external.MemoryProvider;
import com.openjiuwen.service.adapters.agentcore.memory.MemoryStoreMemoryProvider;
import com.openjiuwen.service.adapters.agentcore.memory.MemoryStoreFactory;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the long-term memory adapter.
 *
 * <p>{@code memory.enabled=true} exposes a governed {@link MemoryStore} bean. Consumers
 * (Agents or business code) decide when to call search/add/get/delete.
 *
 * @since 0.1.0
 */
@AutoConfiguration(after = MiddlewareAdaptersAutoConfiguration.class)
@ConditionalOnClass(RunnerConfig.class)
@EnableConfigurationProperties(MiddlewareProperties.class)
public class MemoryAdaptersAutoConfiguration {
    /**
     * Exposes the runtime memory store when memory is enabled.
     *
     * @param middlewareProperties middleware properties
     * @param credentialDecryptor credential decryptor
     * @return the configured memory store
     */
    @Bean
    @ConditionalOnProperty(prefix = "openjiuwen.service.middleware.memory", name = "enabled", havingValue = "true")
    public MemoryStore memoryStore(MiddlewareProperties middlewareProperties, CredentialDecryptor credentialDecryptor) {
        MiddlewareProperties.Memory memory = middlewareProperties.getMemory();
        String apiKey = credentialDecryptor.decrypt(memory.getEncryptedApiKey(), CredentialSceneType.MEMORY_API_KEY);
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "openjiuwen.service.middleware.memory.enabled=true but encrypted-api-key resolves to empty");
        }
        return MemoryStoreFactory.create(apiKey, memory);
    }

    /**
     * Exposes the core memory provider bridge when memory is enabled.
     *
     * @param memoryStore runtime memory store
     * @param middlewareProperties middleware properties
     * @return core memory provider bridge
     */
    @Bean
    @ConditionalOnMissingBean(MemoryProvider.class)
    @ConditionalOnProperty(prefix = "openjiuwen.service.middleware.memory", name = "enabled", havingValue = "true")
    public MemoryProvider runtimeMemoryProvider(MemoryStore memoryStore, MiddlewareProperties middlewareProperties) {
        return new MemoryStoreMemoryProvider(memoryStore, middlewareProperties.getMemory());
    }
}
