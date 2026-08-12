/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPooled;

import org.junit.jupiter.api.Test;

/**
 * RedisJedisClientFactoryTest
 *
 * @since 2026-07-03
 */
class RedisJedisClientFactoryTest {
    @Test
    void createsJedisWithoutConnectingWhenNoAuth() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("127.0.0.1");
        endpoint.setPort(6379);
        endpoint.setDatabase(0);
        endpoint.setTimeoutMs(5000);

        Jedis jedis = RedisJedisClientFactory.createClient(resolve(endpoint), "");
        assertThat(jedis).isNotNull();
        assertThat(jedis.isConnected()).isFalse();
        jedis.close();
    }

    @Test
    void createsJedisPooledWithoutConnectingWhenNoAuth() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("127.0.0.1");
        endpoint.setPort(6379);
        endpoint.setDatabase(0);
        endpoint.setTimeoutMs(5000);

        try (JedisPooled jedis = RedisJedisClientFactory.createPooled(resolve(endpoint), "")) {
            assertThat(jedis).isNotNull();
        }
    }

    @Test
    void appliesTimeoutWithoutAuthenticationOrNonDefaultDatabase() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("127.0.0.1");
        endpoint.setDatabase(0);
        endpoint.setTimeoutMs(1234);

        JedisClientConfig config = RedisJedisClientFactory.clientConfig(resolve(endpoint), "");

        assertThat(config.getConnectionTimeoutMillis()).isEqualTo(1234);
        assertThat(config.getSocketTimeoutMillis()).isEqualTo(1234);
        assertThat(config.getDatabase()).isZero();
        assertThat(config.getPassword()).isNull();
    }

    @Test
    void appliesSameTimeoutWithPasswordAndDatabase() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("127.0.0.1");
        endpoint.setDatabase(2);
        endpoint.setTimeoutMs(2345);

        JedisClientConfig config = RedisJedisClientFactory.clientConfig(resolve(endpoint), "secret");

        assertThat(config.getConnectionTimeoutMillis()).isEqualTo(2345);
        assertThat(config.getSocketTimeoutMillis()).isEqualTo(2345);
        assertThat(config.getDatabase()).isEqualTo(2);
        assertThat(config.getPassword()).isEqualTo("secret");
    }

    private static ResolvedRedisEndpoint resolve(MiddlewareProperties.RedisEndpoint endpoint) {
        return RedisConnectionAssembler.resolveEndpoint("default", endpoint);
    }
}
