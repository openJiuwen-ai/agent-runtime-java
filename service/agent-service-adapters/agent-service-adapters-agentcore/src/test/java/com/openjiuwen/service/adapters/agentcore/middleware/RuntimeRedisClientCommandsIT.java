/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.middleware;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.JedisPooledRuntimeRedisClient;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisConnectionAssembler;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisJedisClientFactory;
import com.openjiuwen.service.adapters.common.middleware.redis.ResolvedRedisEndpoint;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Integration tests for the structured command and eval batch against a real Redis
 * (Testcontainers or local 127.0.0.1:6379).
 *
 * @since 0.1.3
 */
@Tag("system-test")
class RuntimeRedisClientCommandsIT {
    private static final String LOCAL_REDIS_HOST = "127.0.0.1";

    private static final int LOCAL_REDIS_PORT = 6379;

    /**
     * Single-key conditional hash write mirroring the CAS semantics consumers express through eval:
     * 1 = field absent and initialized, 2 = expected flag matched and advanced, 0 = conflict.
     */
    private static final String FIELD_CAS_SCRIPT = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if current == false then
                redis.call('HSET', KEYS[1], ARGV[1], ARGV[4])
                return 1
            end
            if current == ARGV[2] then
                redis.call('HSET', KEYS[1], ARGV[1], ARGV[3])
                return 2
            end
            return 0
            """;

    private String localRedisCleanupPrefix;

    @AfterEach
    void tearDown() {
        if (localRedisCleanupPrefix != null && isLocalRedisReachable()) {
            deleteRedisKeysByPrefix(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT, localRedisCleanupPrefix);
            localRedisCleanupPrefix = null;
        }
    }

    @Test
    void dockerRedisExecutesStructuredCommandsAndEval() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for Redis IT");

        try (GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)) {
            redis.start();
            runStructuredCommandAssertions(redis.getHost(), redis.getMappedPort(6379),
                    "it:commands:" + UUID.randomUUID() + ":");
        }
    }

    @Test
    @Tag("smoke")
    void localRedisExecutesStructuredCommandsAndEval() {
        assumeTrue(isLocalRedisReachable(), "Local Redis on 127.0.0.1:6379 is required for this IT");

        localRedisCleanupPrefix = "it:commands:" + UUID.randomUUID() + ":";
        runStructuredCommandAssertions(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT, localRedisCleanupPrefix);
    }

    private static void runStructuredCommandAssertions(String host, int port, String prefix) {
        try (JedisPooledRuntimeRedisClient client = redisClient(host, port)) {
            String hashKey = prefix + "hash";
            assertThat(client.hset(hashKey, "f1", "a")).isEqualTo(1L);
            assertThat(client.hset(hashKey, "f1", "b")).isZero();
            assertThat(client.hget(hashKey, "f1")).isEqualTo("b");
            assertThat(client.hget(hashKey, "missing")).isNull();
            assertThat(client.hincrBy(hashKey, "count", 5L)).isEqualTo(5L);
            assertThat(client.hincrBy(hashKey, "count", -2L)).isEqualTo(3L);
            assertThat(client.hgetAll(hashKey)).isEqualTo(Map.of("f1", "b", "count", "3"));
            assertThat(client.hdel(hashKey, "f1", "count")).isEqualTo(2L);
            assertThat(client.hgetAll(hashKey)).isEmpty();

            String setKey = prefix + "set";
            assertThat(client.sadd(setKey, "m1", "m2", "m2")).isEqualTo(2L);
            assertThat(client.sismember(setKey, "m1")).isTrue();
            assertThat(client.sismember(setKey, "m9")).isFalse();
            assertThat(client.srem(setKey, "m1", "m9")).isEqualTo(1L);

            String evalKey = prefix + "eval";
            assertThat(client.eval(FIELD_CAS_SCRIPT, List.of(evalKey), "state", "OPEN", "WAIT", "RUNNING"))
                    .isEqualTo(1L);
            assertThat(client.hget(evalKey, "state")).isEqualTo("RUNNING");
            assertThat(client.eval(FIELD_CAS_SCRIPT, List.of(evalKey), "state", "RUNNING", "CLOSED", "unused"))
                    .isEqualTo(2L);
            assertThat(client.hget(evalKey, "state")).isEqualTo("CLOSED");
            assertThat(client.eval(FIELD_CAS_SCRIPT, List.of(evalKey), "state", "RESUMING", "DONE", "unused"))
                    .isEqualTo(0L);
            assertThat(client.hget(evalKey, "state")).isEqualTo("CLOSED");
        }
    }

    private static JedisPooledRuntimeRedisClient redisClient(String host, int port) {
        MiddlewareProperties properties = new MiddlewareProperties();
        properties.getCheckpointer().setType("redis");
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost(host);
        endpoint.setPort(port);
        properties.getRedis().put("default", endpoint);
        ResolvedRedisEndpoint resolved = RedisConnectionAssembler.resolve(properties, "default");
        return new JedisPooledRuntimeRedisClient(RedisJedisClientFactory.createPooled(resolved, ""));
    }

    private static boolean isLocalRedisReachable() {
        try (Jedis jedis = new Jedis(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT)) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (JedisConnectionException ex) {
            return false;
        }
    }

    private static void deleteRedisKeysByPrefix(String host, int port, String prefix) {
        try (Jedis jedis = new Jedis(host, port)) {
            ScanParams params = new ScanParams().match(prefix + "*").count(100);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> scan = jedis.scan(cursor, params);
                for (String key : scan.getResult()) {
                    jedis.del(key);
                }
                cursor = scan.getCursor();
            } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        }
    }
}
