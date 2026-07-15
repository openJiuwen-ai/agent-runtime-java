/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPooled;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for runtime Redis middleware clients.
 *
 * @since 0.1.0
 */
@AutoConfiguration
@ConditionalOnClass({JedisPooled.class, JedisCluster.class})
@EnableConfigurationProperties(MiddlewareProperties.class)
public class RedisMiddlewareAutoConfiguration {
    /**
     * Creates the fallback runtime Redis client when Redis checkpointer is enabled.
     *
     * @param properties middleware properties
     * @param decryptor credential decryptor
     * @return runtime Redis client
     */
    @Bean
    @ConditionalOnMissingBean(RuntimeRedisClient.class)
    @ConditionalOnProperty(prefix = "openjiuwen.service.middleware.checkpointer", name = "type", havingValue = "redis")
    public RuntimeRedisClient runtimeRedisClient(MiddlewareProperties properties, CredentialDecryptor decryptor) {
        String redisRef = properties.getCheckpointer().getRedisRef();
        MiddlewareProperties.RedisEndpoint endpoint = RedisConnectionAssembler.resolveEndpoint(properties, redisRef);
        String password = decryptor.decrypt(endpoint.getEncryptedPassword(), CredentialSceneType.REDIS_PASSWORD);
        if (RedisConnectionAssembler.TYPE_CLUSTER.equals(RedisConnectionAssembler.resolveEndpointType(endpoint))) {
            return new JedisClusterRuntimeRedisClient(RedisJedisClientFactory.createCluster(endpoint, password));
        }
        return new JedisPooledRuntimeRedisClient(RedisJedisClientFactory.createPooled(endpoint, password));
    }

    /**
     * Emits non-sensitive Redis data source diagnostics after the effective runtime Redis client is available.
     *
     * @param properties middleware properties
     * @param redisClientProvider runtime Redis client provider
     * @return Redis datasource diagnostics
     */
    @Bean
    @ConditionalOnProperty(prefix = "openjiuwen.service.middleware.checkpointer", name = "type", havingValue = "redis")
    public RedisDatasourceDiagnostics redisDatasourceDiagnostics(MiddlewareProperties properties,
            ObjectProvider<RuntimeRedisClient> redisClientProvider) {
        return new RedisDatasourceDiagnostics(properties, redisClientProvider);
    }
}
