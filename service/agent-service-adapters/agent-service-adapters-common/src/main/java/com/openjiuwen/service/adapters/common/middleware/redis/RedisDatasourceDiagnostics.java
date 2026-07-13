/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Emits non-sensitive diagnostics for the effective runtime Redis datasource.
 *
 * @since 0.1.0
 */
public class RedisDatasourceDiagnostics implements SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(RedisDatasourceDiagnostics.class);

    private final MiddlewareProperties properties;

    private final ObjectProvider<RuntimeRedisClient> redisClientProvider;

    /**
     * Creates Redis datasource diagnostics.
     *
     * @param properties middleware properties
     * @param redisClientProvider runtime Redis client provider
     */
    public RedisDatasourceDiagnostics(MiddlewareProperties properties,
            ObjectProvider<RuntimeRedisClient> redisClientProvider) {
        this.properties = properties;
        this.redisClientProvider = redisClientProvider;
    }

    @Override
    public void afterSingletonsInstantiated() {
        RuntimeRedisClient redisClient = redisClientProvider.getIfAvailable();
        if (redisClient == null) {
            return;
        }
        String redisRef = properties.getCheckpointer().getRedisRef();
        MiddlewareProperties.RedisEndpoint endpoint = RedisConnectionAssembler.resolveEndpoint(properties, redisRef);
        String endpointType = RedisConnectionAssembler.resolveEndpointType(endpoint);
        log.info(diagnosticMessage(properties, redisClient));
        if (RedisConnectionAssembler.TYPE_CLUSTER.equals(endpointType) && endpoint.getDatabase() != 0) {
            log.info("Runtime Redis cluster ignores standalone-only database setting: redis-ref={}, databaseIgnored={}",
                    normalizedRef(redisRef), endpoint.getDatabase());
        }
    }

    static String diagnosticMessage(MiddlewareProperties properties, RuntimeRedisClient redisClient) {
        String redisRef = properties.getCheckpointer().getRedisRef();
        MiddlewareProperties.RedisEndpoint endpoint = RedisConnectionAssembler.resolveEndpoint(properties, redisRef);
        String endpointType = RedisConnectionAssembler.resolveEndpointType(endpoint);
        return "Runtime Redis datasource selected: redis-ref=" + normalizedRef(redisRef) + ", endpoint-type="
                + endpointType + ", RuntimeRedisClient=" + redisClient.getClass().getSimpleName() + ", ttl-seconds="
                + properties.getCheckpointer().getTtlSeconds() + ", "
                + RedisConnectionAssembler.safeSummary(redisRef, endpoint);
    }

    private static String normalizedRef(String redisRef) {
        return redisRef == null || redisRef.isBlank() ? "default" : redisRef.trim();
    }
}
