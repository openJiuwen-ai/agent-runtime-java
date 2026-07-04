/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.util.Optional;

/**
 * Creates Jedis clients for middleware Redis connections (adapter-side Redis
 * SDK).
 *
 * @since 0.1.0
 */
public final class RedisJedisClientFactory {
    private RedisJedisClientFactory() {
    }

    /**
     * Build a standalone Jedis client from middleware redis endpoint settings.
     * <p>
     * The returned {@link Jedis} is a single connection and is <b>not
     * thread-safe</b>. Use it only from a single
     * thread; for concurrent access use {@link #createPooled(MiddlewareProperties.RedisEndpoint, String)}
     * or {@link #createPool(MiddlewareProperties.RedisEndpoint, String)}.
     *
     * @param endpoint redis host/port/database/timeout from properties
     * @param password decrypted password (blank = no auth)
     * @return Jedis
     */
    public static Jedis createClient(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        HostAndPort hostAndPort = hostAndPort(endpoint);
        return clientConfig(endpoint, password).map(cfg -> new Jedis(hostAndPort, cfg))
            .orElseGet(() -> new Jedis(hostAndPort));
    }

    /**
     * Build a thread-safe {@link JedisPooled} client from middleware redis endpoint settings.
     * <p>
     * Prefer this over {@link #createClient} when the client is shared across threads (e.g. Core
     * {@code RedisStore} / checkpointer), because {@link JedisPooled} exposes the same command API as
     * {@link Jedis} while pooling connections internally.
     *
     * @param endpoint redis host/port/database/timeout from properties
     * @param password decrypted password (blank = no auth)
     * @return a pooled, thread-safe Jedis client
     */
    public static JedisPooled createPooled(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        HostAndPort hostAndPort = hostAndPort(endpoint);
        JedisClientConfig clientConfig = clientConfig(endpoint, password).orElseGet(
            () -> DefaultJedisClientConfig.builder().build());
        return new JedisPooled(hostAndPort, clientConfig, pooledConnectionConfig());
    }

    /**
     * Build a thread-safe {@link JedisPool} from middleware redis endpoint
     * settings. Each borrowed connection must be
     * returned (e.g. via try-with-resources) so it can be reused or discarded if
     * broken.
     *
     * @param endpoint redis host/port/database/timeout from properties
     * @param password decrypted password (blank = no auth)
     * @return a pooled, thread-safe Jedis client
     */
    public static JedisPool createPool(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        HostAndPort hostAndPort = hostAndPort(endpoint);
        JedisClientConfig clientConfig = clientConfig(endpoint, password).orElseGet(
            () -> DefaultJedisClientConfig.builder().build());
        return new JedisPool(poolConfig(), hostAndPort, clientConfig);
    }

    private static HostAndPort hostAndPort(MiddlewareProperties.RedisEndpoint endpoint) {
        String host = endpoint.getHost() != null ? endpoint.getHost().trim() : "localhost";
        int port = endpoint.getPort() > 0 ? endpoint.getPort() : 6379;
        return new HostAndPort(host, port);
    }

    /**
     * Builds a client config carrying password/database/timeouts, or
     * {@link Optional#empty()} when neither password
     * nor a non-zero database is set (preserving the original no-config standalone
     * behaviour).
     *
     * @param password password
     * @param endpoint endpoint
     * @return Optional<JedisClientConfig>
     */
    private static Optional<JedisClientConfig> clientConfig(MiddlewareProperties.RedisEndpoint endpoint,
        String password) {
        int timeoutMs = endpoint.getTimeoutMs() > 0 ? endpoint.getTimeoutMs() : 3000;
        boolean hasPassword = password != null && !password.isBlank();
        int database = endpoint.getDatabase();
        if (!hasPassword && database <= 0) {
            return Optional.empty();
        }
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
            .connectionTimeoutMillis(timeoutMs)
            .socketTimeoutMillis(timeoutMs);
        if (hasPassword) {
            builder.password(password);
        }
        if (database > 0) {
            builder.database(database);
        }
        return Optional.of(builder.build());
    }

    private static GenericObjectPoolConfig<Jedis> poolConfig() {
        GenericObjectPoolConfig<Jedis> config = new GenericObjectPoolConfig<>();
        applyPoolLimits(config);
        return config;
    }

    private static GenericObjectPoolConfig<Connection> pooledConnectionConfig() {
        GenericObjectPoolConfig<Connection> config = new GenericObjectPoolConfig<>();
        applyPoolLimits(config);
        return config;
    }

    private static void applyPoolLimits(GenericObjectPoolConfig<?> config) {
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(1);
        config.setTestOnBorrow(true);
        config.setTestWhileIdle(true);
    }
}
