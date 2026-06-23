/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;

/**
 * Creates Jedis clients for middleware Redis connections (adapter-side Redis SDK).
 */
public final class RedisJedisClientFactory {

    private RedisJedisClientFactory() {
    }

    /**
     * Build a standalone Jedis client from middleware redis endpoint settings.
     *
     * @param endpoint  redis host/port/database/timeout from properties
     * @param password  decrypted password (blank = no auth)
     */
    public static Jedis createClient(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        String host = endpoint.getHost() != null ? endpoint.getHost().trim() : "localhost";
        int port = endpoint.getPort() > 0 ? endpoint.getPort() : 6379;
        int timeoutMs = endpoint.getTimeoutMs() > 0 ? endpoint.getTimeoutMs() : 3000;
        HostAndPort hostAndPort = new HostAndPort(host, port);

        boolean hasPassword = password != null && !password.isBlank();
        int database = endpoint.getDatabase();
        if (hasPassword || database > 0) {
            DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                    .connectionTimeoutMillis(timeoutMs)
                    .socketTimeoutMillis(timeoutMs);
            if (hasPassword) {
                builder.password(password);
            }
            if (database > 0) {
                builder.database(database);
            }
            return new Jedis(hostAndPort, builder.build());
        }
        return new Jedis(hostAndPort);
    }
}
