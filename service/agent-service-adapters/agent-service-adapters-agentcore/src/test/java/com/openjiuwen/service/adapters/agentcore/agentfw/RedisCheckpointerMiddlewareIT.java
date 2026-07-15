/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.service.adapters.agentcore.middleware.DefaultMiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.credential.PassthroughCredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.JedisPooledRuntimeRedisClient;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisJedisClientFactory;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.Jedis;
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

/**
 * Integration tests against a real Redis (Testcontainers or local
 * 127.0.0.1:6379).
 *
 * @since 0.1.0
 */
@Tag("system-test")
class RedisCheckpointerMiddlewareIT {
    private static final String LOCAL_REDIS_HOST = "127.0.0.1";

    private static final int LOCAL_REDIS_PORT = 6379;

    private String localRedisCleanupPrefix;

    @AfterEach
    void tearDown() {
        if (localRedisCleanupPrefix != null && isLocalRedisReachable()) {
            deleteRedisKeysByPrefix(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT, localRedisCleanupPrefix, null);
            localRedisCleanupPrefix = null;
        }
        resetRunnerEnvironment();
    }

    @Test
    @SuppressWarnings("unchecked")
    void dockerRedisRestoresSessionOnHandlerRestart() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for Redis IT");

        try (GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)) {
            redis.start();

            RedisSessionItContext ctx = new RedisSessionItContext(redis.getHost(), redis.getMappedPort(6379), "",
                    "c-redis-it", new PassthroughCredentialDecryptor(), null, "a", "b");
            SessionRestoreResult result = runSessionRestoreAcrossHandlerRestarts(ctx, true);

            assertThat((Map<String, Object>) result.first().getResult()).containsEntry("content", "turn1:a");
            assertThat((Map<String, Object>) result.second().getResult()).containsEntry("content", "turn2:b|prev=a");
        }
    }

    @Test
    @Tag("smoke")
    @SuppressWarnings("unchecked")
    void localRedisRestoresSessionOnHandlerRestart() {
        assumeTrue(isLocalRedisReachable(), "Local Redis on 127.0.0.1:6379 is required for this IT");

        String conversationId = "c-redis-local-it";
        localRedisCleanupPrefix = conversationId + ":";
        deleteRedisKeysByPrefix(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT, localRedisCleanupPrefix, null);

        RedisSessionItContext ctx = new RedisSessionItContext(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT, "", conversationId,
                new PassthroughCredentialDecryptor(), null, "a", "b");
        SessionRestoreResult result = runSessionRestoreAcrossHandlerRestarts(ctx, true);

        assertThat((Map<String, Object>) result.first().getResult()).containsEntry("content", "turn1:a");
        assertThat((Map<String, Object>) result.second().getResult()).containsEntry("content", "turn2:b|prev=a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void localRedisRestoresSessionSameHandler() {
        assumeTrue(isLocalRedisReachable(), "Local Redis on 127.0.0.1:6379 is required for this IT");

        String conversationId = "c-redis-local-same";
        localRedisCleanupPrefix = conversationId + ":";
        deleteRedisKeysByPrefix(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT, localRedisCleanupPrefix, null);

        RedisSessionItContext ctx = new RedisSessionItContext(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT, "", conversationId,
                new PassthroughCredentialDecryptor(), null, "a", "b");
        SessionRestoreResult result = runTwoTurnsSameHandler(ctx);

        assertThat((Map<String, Object>) result.first().getResult()).containsEntry("content", "turn1:a");
        assertThat((Map<String, Object>) result.second().getResult()).containsEntry("content", "turn2:b|prev=a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisAuthWithPassthroughDecryptor() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for Redis IT");

        try (GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "secret")) {
            redis.start();

            RedisSessionItContext ctx = new RedisSessionItContext(redis.getHost(), redis.getMappedPort(6379), "secret",
                    "c-redis-auth", new PassthroughCredentialDecryptor(), null, "x", "y");
            SessionRestoreResult result = runTwoTurnsSameHandler(ctx);

            assertThat((Map<String, Object>) result.first().getResult()).containsEntry("content", "turn1:x");
            assertThat((Map<String, Object>) result.second().getResult()).containsEntry("content", "turn2:y|prev=x");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void customDecryptorConnectsToAuthRedis() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for Redis IT");

        try (GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379).withCommand("redis-server", "--requirepass", "real-secret")) {
            redis.start();

            CredentialDecryptor decryptor = ciphertext -> ciphertext != null && ciphertext.startsWith("ENC:")
                    ? ciphertext.substring(4)
                    : ciphertext;
            RedisSessionItContext ctx = new RedisSessionItContext(redis.getHost(), redis.getMappedPort(6379),
                    "ENC:real-secret", "c-redis-decrypt", decryptor, "real-secret", "one", "two");
            SessionRestoreResult result = runTwoTurnsSameHandler(ctx);

            assertThat((Map<String, Object>) result.first().getResult()).containsEntry("content", "turn1:one");
            Map<String, Object> second = (Map<String, Object>) result.second().getResult();
            assertThat(second).containsEntry("content", "turn2:two|prev=one");
        }
    }

    private static SessionRestoreResult runSessionRestoreAcrossHandlerRestarts(RedisSessionItContext ctx,
            boolean verifyRedisKeys) {
        MiddlewareProperties properties = redisProperties(ctx.host(), ctx.port(), ctx.encryptedPassword());
        DefaultMiddlewareAdapterRegistrar registrar = new DefaultMiddlewareAdapterRegistrar(properties, ctx.decryptor(),
                runtimeRedisClient(properties, ctx.decryptor()));

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler firstHandler = new JiuwenCoreAgentHandler(
                new JiuwenCoreAgentHandlerTest.SessionEchoAgent(), registrar);
        firstHandler.start();
        QueryResponse first = firstHandler.query(request(ctx.conversationId(), ctx.firstQuery()));
        firstHandler.stop();

        if (verifyRedisKeys) {
            assertThat(countRedisKeys(ctx.host(), ctx.port(), ctx.conversationId() + ":", ctx.redisPasswordForAdmin()))
                    .isGreaterThan(0);
        }

        JiuwenCoreAgentHandler secondHandler = new JiuwenCoreAgentHandler(
                new JiuwenCoreAgentHandlerTest.SessionEchoAgent(), registrar);
        secondHandler.start();
        QueryResponse second = secondHandler.query(request(ctx.conversationId(), ctx.secondQuery()));
        secondHandler.stop();

        return new SessionRestoreResult(first, second);
    }

    private static SessionRestoreResult runTwoTurnsSameHandler(RedisSessionItContext ctx) {
        MiddlewareProperties properties = redisProperties(ctx.host(), ctx.port(), ctx.encryptedPassword());
        DefaultMiddlewareAdapterRegistrar registrar = new DefaultMiddlewareAdapterRegistrar(properties, ctx.decryptor(),
                runtimeRedisClient(properties, ctx.decryptor()));

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler handler = new JiuwenCoreAgentHandler(new JiuwenCoreAgentHandlerTest.SessionEchoAgent(),
                registrar);
        handler.start();
        QueryResponse first = handler.query(request(ctx.conversationId(), ctx.firstQuery()));
        QueryResponse second = handler.query(request(ctx.conversationId(), ctx.secondQuery()));
        handler.stop();

        return new SessionRestoreResult(first, second);
    }

    private static boolean isLocalRedisReachable() {
        try (Jedis jedis = new Jedis(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT)) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception ex) {
            return false;
        }
    }

    private static RuntimeRedisClient runtimeRedisClient(MiddlewareProperties properties,
            CredentialDecryptor decryptor) {
        MiddlewareProperties.RedisEndpoint endpoint = properties.getRedis().get("default");
        String password = decryptor.decrypt(endpoint.getEncryptedPassword(), CredentialSceneType.REDIS_PASSWORD);
        return new JedisPooledRuntimeRedisClient(RedisJedisClientFactory.createPooled(endpoint, password));
    }

    private static MiddlewareProperties redisProperties(String host, int port, String encryptedPassword) {
        MiddlewareProperties properties = new MiddlewareProperties();
        properties.getCheckpointer().setType("redis");
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost(host);
        endpoint.setPort(port);
        endpoint.setDatabase(0);
        endpoint.setEncryptedPassword(encryptedPassword);
        properties.getRedis().put("default", endpoint);
        return properties;
    }

    private static ServeRequest request(String conversationId, String content) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        request.setMessages(List.of(Map.of("role", "user", "content", content)));
        request.setUserId("anonymous");
        request.setSpaceId("default");
        return request;
    }

    private static void resetRunnerEnvironment() {
        try {
            Runner.stop();
        } catch (Exception ignored) {
            // Runner may not be started
        }
        // Reset DEFAULT singleton's checkpointerConfig which may have been
        // mutated by DefaultMiddlewareAdapterRegistrar.applyToRunnerConfig().
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(null);
        RunnerConfig.setRunnerConfig(null);
        CheckpointerFactory.setDefaultCheckpointer(null);
    }

    private static long countRedisKeys(String host, int port, String prefix, String password) {
        try (Jedis jedis = openJedis(host, port, password)) {
            return scanKeyCount(jedis, prefix);
        }
    }

    private static void deleteRedisKeysByPrefix(String host, int port, String prefix, String password) {
        try (Jedis jedis = openJedis(host, port, password)) {
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

    private static Jedis openJedis(String host, int port, String password) {
        Jedis jedis = new Jedis(host, port);
        if (password != null && !password.isBlank()) {
            jedis.auth(password);
        }
        return jedis;
    }

    private static long scanKeyCount(Jedis jedis, String prefix) {
        ScanParams params = new ScanParams().match(prefix + "*").count(100);
        String cursor = ScanParams.SCAN_POINTER_START;
        long count = 0L;
        do {
            ScanResult<String> scan = jedis.scan(cursor, params);
            count += scan.getResult().size();
            cursor = scan.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        return count;
    }

    private record RedisSessionItContext(String host, int port, String encryptedPassword, String conversationId,
            CredentialDecryptor decryptor, String redisPasswordForAdmin, String firstQuery, String secondQuery) {
    }

    private record SessionRestoreResult(QueryResponse first, QueryResponse second) {
    }
}
