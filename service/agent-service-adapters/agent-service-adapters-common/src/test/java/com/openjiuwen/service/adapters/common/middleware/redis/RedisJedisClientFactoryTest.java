/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import redis.clients.jedis.Jedis;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RedisJedisClientFactoryTest {

    @Test
    void createsJedisWithoutConnectingWhenNoAuth() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("127.0.0.1");
        endpoint.setPort(6379);
        endpoint.setDatabase(0);
        endpoint.setTimeoutMs(5000);

        Jedis jedis = RedisJedisClientFactory.createClient(endpoint, "");
        assertThat(jedis).isNotNull();
        assertThat(jedis.isConnected()).isFalse();
        jedis.close();
    }
}
