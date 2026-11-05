/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import com.openjiuwen.core.foundation.store.BaseKVStore;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerConfig;
import com.openjiuwen.service.adapters.agentcore.middleware.CheckpointKVStoreProvider;
import com.openjiuwen.service.adapters.agentcore.middleware.DefaultMiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisMiddlewareAutoConfiguration;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

import java.util.Map;

/**
 * Auto-configuration for middleware adapters: binds
 * {@link MiddlewareProperties},
 * registers {@link MiddlewareAdapterRegistrar}, and applies checkpointer
 * settings to {@link RunnerConfig}.
 *
 * @since 0.1.0
 */
@AutoConfiguration(before = AgentCoreAdaptersAutoConfiguration.class, after = RedisMiddlewareAutoConfiguration.class)
@ConditionalOnClass(RunnerConfig.class)
@EnableConfigurationProperties(MiddlewareProperties.class)
public class MiddlewareAdaptersAutoConfiguration {
    /**
     * Registers the middleware adapter registrar bean and applies checkpointer config.
     *
     * @param middlewareProperties the middleware properties
     * @param credentialDecryptor the credential decryptor
     * @param redisClientProvider the runtime Redis client provider
     * @param storeProviders application-provided asynchronous checkpoint stores
     * @return the middleware adapter registrar
     */
    @Bean
    public MiddlewareAdapterRegistrar middlewareAdapterRegistrar(MiddlewareProperties middlewareProperties,
            CredentialDecryptor credentialDecryptor, ObjectProvider<RuntimeRedisClient> redisClientProvider,
            ObjectProvider<CheckpointKVStoreProvider> storeProviders) {
        String configuredType = middlewareProperties.getCheckpointer().getType();
        String type = configuredType == null || configuredType.isBlank() ? "in_memory" : configuredType.trim();
        MiddlewareAdapterRegistrar registrar;
        if ("in_memory".equals(type) || "redis".equals(type)) {
            registrar = new DefaultMiddlewareAdapterRegistrar(middlewareProperties,
                    credentialDecryptor, redisClientProvider.getIfAvailable());
        } else {
            var matches = storeProviders.stream().filter(provider -> type.equals(provider.type())).toList();
            if (matches.size() != 1) {
                throw new IllegalStateException("Expected one CheckpointKVStoreProvider for type " + type
                        + ", found " + matches.size());
            }
            String ref = middlewareProperties.getCheckpointer().getBackendRef();
            if (ref == null || ref.isBlank()) {
                throw new IllegalArgumentException("checkpointer.backend-ref must not be blank for type " + type);
            }
            BaseKVStore store = matches.get(0).getStore(ref);
            if (store == null) {
                throw new IllegalStateException("CheckpointKVStoreProvider returned null for type " + type);
            }
            CheckpointerConfig config = CheckpointerConfig.fromMap(
                    Map.of("type", "persistence", "conf", Map.of("kv_store", store)));
            registrar = runnerConfig -> runnerConfig.setCheckpointerConfig(config);
        }
        registrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig());
        return registrar;
    }
}
