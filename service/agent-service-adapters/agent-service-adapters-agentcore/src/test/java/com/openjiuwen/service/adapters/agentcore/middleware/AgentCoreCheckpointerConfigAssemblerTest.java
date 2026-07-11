/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * AgentCoreCheckpointerConfigAssemblerTest
 *
 * @since 2026-07-03
 */
class AgentCoreCheckpointerConfigAssemblerTest {
    @Test
    void buildsInMemoryConfigByDefault() {
        MiddlewareProperties properties = new MiddlewareProperties();
        Map<String, Object> config = AgentCoreCheckpointerConfigAssembler.build(properties, ciphertext -> ciphertext,
                null);
        assertThat(config.get("type")).isEqualTo("in_memory");
        assertThat(config.get("conf")).isEqualTo(Map.of());
    }

    @Test
    void buildsRedisConfigWithRuntimeRedisClient() {
        MiddlewareProperties properties = new MiddlewareProperties();
        properties.getCheckpointer().setType("redis");
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("127.0.0.1");
        endpoint.setPort(6379);
        properties.getRedis().put("default", endpoint);
        RuntimeRedisClient redisClient = new NoopRuntimeRedisClient();

        Map<String, Object> config = AgentCoreCheckpointerConfigAssembler.build(properties, ciphertext -> ciphertext,
                redisClient);
        assertThat(config.get("type")).isEqualTo("redis");
        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) config.get("conf");
        @SuppressWarnings("unchecked")
        Map<String, Object> connection = (Map<String, Object>) conf.get("connection");
        assertThat(connection.get("url")).asString().contains("127.0.0.1:6379");
        assertThat(connection.get("redis_client")).isSameAs(redisClient);
        assertThat(conf.get("ttl")).isEqualTo(Map.of("default_ttl", 10080.0d, "refresh_on_read", false));
    }

    @Test
    void convertsCheckpointerTtlSecondsToAgentCoreMinutes() {
        MiddlewareProperties properties = new MiddlewareProperties();
        properties.getCheckpointer().setType("redis");
        properties.getCheckpointer().setTtlSeconds(30L);
        properties.getRedis().put("default", new MiddlewareProperties.RedisEndpoint());

        Map<String, Object> config = AgentCoreCheckpointerConfigAssembler.build(properties, value -> value,
                new NoopRuntimeRedisClient());
        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) config.get("conf");

        assertThat(conf.get("ttl")).isEqualTo(Map.of("default_ttl", 0.5d, "refresh_on_read", false));
    }

    @Test
    void redisRequiresEndpointDefinition() {
        MiddlewareProperties properties = new MiddlewareProperties();
        properties.getCheckpointer().setType("redis");
        assertThatThrownBy(
                () -> AgentCoreCheckpointerConfigAssembler.build(properties, s -> s, new NoopRuntimeRedisClient()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("redis.default");
    }

    @Test
    void redisRequiresRuntimeRedisClient() {
        MiddlewareProperties properties = new MiddlewareProperties();
        properties.getCheckpointer().setType("redis");
        properties.getRedis().put("default", new MiddlewareProperties.RedisEndpoint());

        assertThatThrownBy(() -> AgentCoreCheckpointerConfigAssembler.build(properties, s -> s, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RuntimeRedisClient");
    }

    private static final class NoopRuntimeRedisClient implements RuntimeRedisClient {
        @Override
        public Object get(String key) {
            return null;
        }

        @Override
        public byte[] get(byte[] key) {
            return new byte[0];
        }

        @Override
        public String set(String key, String value) {
            return "OK";
        }

        @Override
        public String set(String key, byte[] value) {
            return "OK";
        }

        @Override
        public String set(byte[] key, byte[] value) {
            return "OK";
        }

        @Override
        public String setex(String key, long seconds, String value) {
            return "OK";
        }

        @Override
        public String setex(byte[] key, long seconds, byte[] value) {
            return "OK";
        }

        @Override
        public long setnx(String key, String value) {
            return 0;
        }

        @Override
        public long setnx(byte[] key, byte[] value) {
            return 0;
        }

        @Override
        public long del(String... keys) {
            return 0;
        }

        @Override
        public long del(byte[]... keys) {
            return 0;
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public boolean exists(byte[] key) {
            return false;
        }

        @Override
        public long expire(String key, long seconds) {
            return 0;
        }

        @Override
        public long expire(byte[] key, long seconds) {
            return 0;
        }

        @Override
        public List<Object> mget(String... keys) {
            return List.of();
        }

        @Override
        public List<String> scanIter(String pattern) {
            return List.of();
        }
    }
}
