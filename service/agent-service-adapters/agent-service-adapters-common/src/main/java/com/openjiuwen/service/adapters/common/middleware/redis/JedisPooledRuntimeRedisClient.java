/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.JedisPooled;

/**
 * {@link RuntimeRedisClient} backed by Jedis' pooled standalone command client.
 *
 * @since 0.1.0
 */
public class JedisPooledRuntimeRedisClient extends UnifiedJedisRuntimeRedisClient {
    /**
     * Creates a runtime Redis client.
     *
     * @param delegate pooled Jedis client
     */
    public JedisPooledRuntimeRedisClient(JedisPooled delegate) {
        super(delegate);
    }
}
