/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import redis.clients.jedis.Jedis;

import org.junit.jupiter.api.Test;

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
        Map<String, Object> config = AgentCoreCheckpointerConfigAssembler.build(properties, ciphertext -> ciphertext);
        assertThat(config.get("type")).isEqualTo("in_memory");
        assertThat(config.get("conf")).isEqualTo(Map.of());
    }

    @Test
    void buildsRedisConfigWithJedisClient() {
        MiddlewareProperties properties = new MiddlewareProperties();
        properties.getCheckpointer().setType("redis");
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("127.0.0.1");
        endpoint.setPort(6379);
        properties.getRedis().put("default", endpoint);

        Map<String, Object> config = AgentCoreCheckpointerConfigAssembler.build(properties, ciphertext -> ciphertext);
        assertThat(config.get("type")).isEqualTo("redis");
        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) config.get("conf");
        @SuppressWarnings("unchecked")
        Map<String, Object> connection = (Map<String, Object>) conf.get("connection");
        assertThat(connection.get("url")).asString().contains("127.0.0.1:6379");
        assertThat(connection.get("redis_client")).isInstanceOf(Jedis.class);
    }

    @Test
    void redisRequiresEndpointDefinition() {
        MiddlewareProperties properties = new MiddlewareProperties();
        properties.getCheckpointer().setType("redis");
        assertThatThrownBy(() -> AgentCoreCheckpointerConfigAssembler.build(properties, s -> s))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("redis.default");
    }
}
