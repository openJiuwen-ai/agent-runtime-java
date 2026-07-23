/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import redis.clients.jedis.Connection;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.DefaultJedisSocketFactory;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisClientConfig;
import redis.clients.jedis.JedisCluster;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.JedisSocketFactory;

import org.apache.commons.pool2.impl.GenericObjectPoolConfig;

import java.util.LinkedHashSet;
import java.util.Set;

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
     * thread; for concurrent access use {@link #createPooled(ResolvedRedisEndpoint, String)}
     * or {@link #createPool(ResolvedRedisEndpoint, String)}.
     *
     * @param endpoint redis host/port/database/timeout from properties
     * @param password decrypted password (blank = no auth)
     * @return Jedis
     */
    public static Jedis createClient(ResolvedRedisEndpoint endpoint, String password) {
        HostAndPort hostAndPort = hostAndPort(endpoint);
        JedisClientConfig config = clientConfig(endpoint, password);
        Connection connection = new LazyConfiguredConnection(new DefaultJedisSocketFactory(hostAndPort, config),
                config);
        return new Jedis(connection);
    }

    /**
     * Builds a standalone client after centrally resolving raw endpoint properties.
     *
     * @param endpoint raw endpoint properties
     * @param password decrypted password
     * @return Jedis
     */
    public static Jedis createClient(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        return createClient(RedisConnectionAssembler.resolveEndpoint("default", endpoint), password);
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
    public static JedisPooled createPooled(ResolvedRedisEndpoint endpoint, String password) {
        HostAndPort hostAndPort = hostAndPort(endpoint);
        return new JedisPooled(hostAndPort, clientConfig(endpoint, password), pooledConnectionConfig());
    }

    /**
     * Builds a pooled standalone client after centrally resolving raw endpoint properties.
     *
     * @param endpoint raw endpoint properties
     * @param password decrypted password
     * @return a pooled, thread-safe Jedis client
     */
    public static JedisPooled createPooled(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        return createPooled(RedisConnectionAssembler.resolveEndpoint("default", endpoint), password);
    }

    /**
     * Build a thread-safe {@link JedisCluster} client from middleware redis endpoint settings.
     *
     * @param endpoint redis cluster nodes/timeout from properties
     * @param password decrypted password (blank = no auth)
     * @return a Jedis cluster client
     */
    public static JedisCluster createCluster(ResolvedRedisEndpoint endpoint, String password) {
        requireType(endpoint, RedisConnectionAssembler.TYPE_CLUSTER);
        Set<HostAndPort> nodes = new LinkedHashSet<>();
        for (String node : endpoint.getNodes()) {
            nodes.add(HostAndPort.from(node));
        }
        String previousInitNoError = System.getProperty(JedisCluster.INIT_NO_ERROR_PROPERTY);
        System.setProperty(JedisCluster.INIT_NO_ERROR_PROPERTY, "true");
        try {
            return new JedisCluster(nodes, clientConfig(endpoint, password), 5, pooledConnectionConfig());
        } finally {
            if (previousInitNoError == null) {
                System.clearProperty(JedisCluster.INIT_NO_ERROR_PROPERTY);
            } else {
                System.setProperty(JedisCluster.INIT_NO_ERROR_PROPERTY, previousInitNoError);
            }
        }
    }

    /**
     * Builds a cluster client after centrally resolving raw endpoint properties.
     *
     * @param endpoint raw endpoint properties
     * @param password decrypted password
     * @return a Jedis cluster client
     */
    public static JedisCluster createCluster(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        return createCluster(RedisConnectionAssembler.resolveEndpoint("default", endpoint), password);
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
    public static JedisPool createPool(ResolvedRedisEndpoint endpoint, String password) {
        HostAndPort hostAndPort = hostAndPort(endpoint);
        return new JedisPool(poolConfig(), hostAndPort, clientConfig(endpoint, password));
    }

    /**
     * Builds a standalone pool after centrally resolving raw endpoint properties.
     *
     * @param endpoint raw endpoint properties
     * @param password decrypted password
     * @return a pooled, thread-safe Jedis client
     */
    public static JedisPool createPool(MiddlewareProperties.RedisEndpoint endpoint, String password) {
        return createPool(RedisConnectionAssembler.resolveEndpoint("default", endpoint), password);
    }

    private static HostAndPort hostAndPort(ResolvedRedisEndpoint endpoint) {
        requireType(endpoint, RedisConnectionAssembler.TYPE_STANDALONE);
        return new HostAndPort(endpoint.getHost(), endpoint.getPort());
    }

    /**
     * Builds a client config that always carries the resolved connection and socket timeouts.
     *
     * @param password password
     * @param endpoint endpoint
     * @return Jedis client config
     */
    static JedisClientConfig clientConfig(ResolvedRedisEndpoint endpoint, String password) {
        boolean hasPassword = password != null && !password.isBlank();
        DefaultJedisClientConfig.Builder builder = DefaultJedisClientConfig.builder()
                .connectionTimeoutMillis(endpoint.getTimeoutMs()).socketTimeoutMillis(endpoint.getTimeoutMs());
        if (hasPassword) {
            builder.password(password);
        }
        if (!endpoint.isCluster() && endpoint.getDatabase() > 0) {
            builder.database(endpoint.getDatabase());
        }
        return builder.build();
    }

    private static void requireType(ResolvedRedisEndpoint endpoint, String requiredType) {
        if (!requiredType.equals(endpoint.getType())) {
            throw new IllegalArgumentException("Redis endpoint " + endpoint.getRef() + " has type=" + endpoint.getType()
                    + ", but this client requires type=" + requiredType);
        }
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

    private static final class LazyConfiguredConnection extends Connection {
        private boolean isInitialized;

        private LazyConfiguredConnection(JedisSocketFactory socketFactory, JedisClientConfig clientConfig) {
            super(Connection.builder().socketFactory(socketFactory).clientConfig(clientConfig));
        }

        @Override
        public void connect() {
            if (isInitialized) {
                super.connect();
                return;
            }
            isInitialized = true;
            boolean isInitializationSuccessful = false;
            try {
                initializeFromClientConfig();
                isInitializationSuccessful = true;
            } finally {
                if (!isInitializationSuccessful) {
                    isInitialized = false;
                }
            }
        }
    }
}
