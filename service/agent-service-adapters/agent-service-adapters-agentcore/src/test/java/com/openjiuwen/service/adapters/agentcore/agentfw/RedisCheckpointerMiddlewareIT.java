/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.agentfw;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.service.adapters.agentcore.middleware.DefaultMiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.PassthroughCredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration tests against a real Redis (Testcontainers or local 127.0.0.1:6379).
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
    void redisCheckpointerRestoresSessionAcrossHandlerRestarts() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for Redis IT");

        try (GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)) {
            redis.start();

            SessionRestoreResult result = runSessionRestoreAcrossHandlerRestarts(
                    redis.getHost(),
                    redis.getMappedPort(6379),
                    "",
                    "c-redis-it",
                    new PassthroughCredentialDecryptor(),
                    true,
                    null);

            assertThat((Map<String, Object>) result.first().getResult()).containsEntry("content", "turn1:a");
            assertThat((Map<String, Object>) result.second().getResult()).containsEntry("content", "turn2:b|prev=a");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisCheckpointerRestoresSessionAcrossHandlerRestartsOnLocalRedis() {
        assumeTrue(isLocalRedisReachable(), "Local Redis on 127.0.0.1:6379 is required for this IT");

        String conversationId = "c-redis-local-it";
        localRedisCleanupPrefix = conversationId + ":";
        deleteRedisKeysByPrefix(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT, localRedisCleanupPrefix, null);

        SessionRestoreResult result = runSessionRestoreAcrossHandlerRestarts(
                LOCAL_REDIS_HOST,
                LOCAL_REDIS_PORT,
                "",
                conversationId,
                new PassthroughCredentialDecryptor(),
                true,
                null);

        assertThat((Map<String, Object>) result.first().getResult()).containsEntry("content", "turn1:a");
        assertThat((Map<String, Object>) result.second().getResult()).containsEntry("content", "turn2:b|prev=a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisCheckpointerRestoresSessionOnLocalRedisSameHandler() {
        assumeTrue(isLocalRedisReachable(), "Local Redis on 127.0.0.1:6379 is required for this IT");

        String conversationId = "c-redis-local-same";
        localRedisCleanupPrefix = conversationId + ":";
        deleteRedisKeysByPrefix(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT, localRedisCleanupPrefix, null);

        SessionRestoreResult result = runTwoTurnsSameHandler(
                LOCAL_REDIS_HOST,
                LOCAL_REDIS_PORT,
                "",
                conversationId,
                new PassthroughCredentialDecryptor(),
                null,
                "a",
                "b");

        assertThat((Map<String, Object>) result.first().getResult()).containsEntry("content", "turn1:a");
        assertThat((Map<String, Object>) result.second().getResult()).containsEntry("content", "turn2:b|prev=a");
    }

    @Test
    @SuppressWarnings("unchecked")
    void redisCheckpointerWithEncryptedPasswordPassthrough() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for Redis IT");

        try (GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", "secret")) {
            redis.start();

            SessionRestoreResult result = runTwoTurnsSameHandler(
                    redis.getHost(),
                    redis.getMappedPort(6379),
                    "secret",
                    "c-redis-auth",
                    new PassthroughCredentialDecryptor(),
                    null,
                    "x",
                    "y");

            assertThat((Map<String, Object>) result.first().getResult()).containsEntry("content", "turn1:x");
            assertThat((Map<String, Object>) result.second().getResult()).containsEntry("content", "turn2:y|prev=x");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void customCredentialDecryptorConnectsToPasswordProtectedRedis() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for Redis IT");

        try (GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379)
                .withCommand("redis-server", "--requirepass", "real-secret")) {
            redis.start();

            CredentialDecryptor decryptor = ciphertext ->
                    ciphertext != null && ciphertext.startsWith("ENC:") ? ciphertext.substring(4) : ciphertext;
            SessionRestoreResult result = runTwoTurnsSameHandler(
                    redis.getHost(),
                    redis.getMappedPort(6379),
                    "ENC:real-secret",
                    "c-redis-decrypt",
                    decryptor,
                    "real-secret",
                    "one",
                    "two");

            assertThat((Map<String, Object>) result.first().getResult()).containsEntry("content", "turn1:one");
            assertThat((Map<String, Object>) result.second().getResult()).containsEntry("content", "turn2:two|prev=one");
        }
    }

    private static SessionRestoreResult runSessionRestoreAcrossHandlerRestarts(
            String host,
            int port,
            String encryptedPassword,
            String conversationId,
            CredentialDecryptor decryptor,
            boolean verifyRedisKeys,
            String redisPasswordForAdmin) {
        MiddlewareProperties properties = redisProperties(host, port, encryptedPassword);
        DefaultMiddlewareAdapterRegistrar registrar = new DefaultMiddlewareAdapterRegistrar(properties, decryptor);

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler firstHandler =
                new JiuwenCoreAgentHandler(new JiuwenCoreAgentHandlerTest.SessionEchoAgent(), registrar);
        firstHandler.start();
        QueryResponse first = firstHandler.query(request(conversationId, "a"));
        firstHandler.stop();

        if (verifyRedisKeys) {
            assertThat(countRedisKeys(host, port, conversationId + ":", redisPasswordForAdmin))
                    .isGreaterThan(0);
        }

        JiuwenCoreAgentHandler secondHandler =
                new JiuwenCoreAgentHandler(new JiuwenCoreAgentHandlerTest.SessionEchoAgent(), registrar);
        secondHandler.start();
        QueryResponse second = secondHandler.query(request(conversationId, "b"));
        secondHandler.stop();

        return new SessionRestoreResult(first, second);
    }

    private static SessionRestoreResult runTwoTurnsSameHandler(
            String host,
            int port,
            String encryptedPassword,
            String conversationId,
            CredentialDecryptor decryptor,
            String redisPasswordForAdmin,
            String firstQuery,
            String secondQuery) {
        MiddlewareProperties properties = redisProperties(host, port, encryptedPassword);
        DefaultMiddlewareAdapterRegistrar registrar = new DefaultMiddlewareAdapterRegistrar(properties, decryptor);

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler handler =
                new JiuwenCoreAgentHandler(new JiuwenCoreAgentHandlerTest.SessionEchoAgent(), registrar);
        handler.start();
        QueryResponse first = handler.query(request(conversationId, firstQuery));
        QueryResponse second = handler.query(request(conversationId, secondQuery));
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
        RunnerConfig.setRunnerConfig(null);
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
        long count = 0;
        do {
            ScanResult<String> scan = jedis.scan(cursor, params);
            count += scan.getResult().size();
            cursor = scan.getCursor();
        } while (!ScanParams.SCAN_POINTER_START.equals(cursor));
        return count;
    }

    private record SessionRestoreResult(QueryResponse first, QueryResponse second) {
    }
}
