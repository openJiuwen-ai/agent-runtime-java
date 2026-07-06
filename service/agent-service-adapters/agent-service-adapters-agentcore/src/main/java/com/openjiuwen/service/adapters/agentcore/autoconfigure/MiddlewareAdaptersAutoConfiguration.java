/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import com.openjiuwen.core.runner.RunnerConfig;
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
     * @return the middleware adapter registrar
     */
    @Bean
    public MiddlewareAdapterRegistrar middlewareAdapterRegistrar(MiddlewareProperties middlewareProperties,
            CredentialDecryptor credentialDecryptor, ObjectProvider<RuntimeRedisClient> redisClientProvider) {
        DefaultMiddlewareAdapterRegistrar registrar = new DefaultMiddlewareAdapterRegistrar(middlewareProperties,
                credentialDecryptor, redisClientProvider.getIfAvailable());
        registrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig());
        return registrar;
    }
}
