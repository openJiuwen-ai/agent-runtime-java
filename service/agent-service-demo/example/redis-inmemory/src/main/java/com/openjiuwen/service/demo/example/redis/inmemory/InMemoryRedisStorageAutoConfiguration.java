/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.redis.inmemory;

import com.openjiuwen.service.adapters.common.middleware.redis.RedisMiddlewareAutoConfiguration;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Installs the in-memory {@link RuntimeRedisClient} plugin whenever this jar is on the classpath.
 *
 * <p>Declared before {@link RedisMiddlewareAutoConfiguration} so the native Jedis client backs
 * off through its existing {@code @ConditionalOnMissingBean} guard: the plugin takes over while
 * present, and the native client returns automatically once the jar is removed.
 *
 * @since 0.1.3
 */
@AutoConfiguration(before = RedisMiddlewareAutoConfiguration.class)
public class InMemoryRedisStorageAutoConfiguration {
    /**
     * Creates the in-memory runtime Redis client.
     *
     * @return runtime Redis client backed by process-local maps
     */
    @Bean
    public RuntimeRedisClient runtimeRedisClient() {
        return new InMemoryRuntimeRedisClient();
    }
}
