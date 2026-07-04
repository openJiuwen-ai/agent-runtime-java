/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Spring full-chain IT: properties → middleware auto-configuration
 * → {@link MiddlewareAdapterRegistrar} → {@link JiuwenCoreAgentHandler#start()}
 * + two queries
 * against local passwordless Redis.
 *
 * @since 0.1.0
 */
@Tag("system-test")
class MiddlewareRedisSpringIT {
    private static final String LOCAL_REDIS_HOST = "127.0.0.1";

    private static final int LOCAL_REDIS_PORT = 6379;

    private String localRedisCleanupPrefix;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(CredentialDecryptorAutoConfiguration.class, MiddlewareAdaptersAutoConfiguration.class,
                AgentCoreAdaptersAutoConfiguration.class))
        .withUserConfiguration(TestAgentHandlerConfiguration.class)
        .withPropertyValues("openjiuwen.service.agent-id=spring-it-agent",
            "openjiuwen.service.middleware.checkpointer.type=redis",
            "openjiuwen.service.middleware.redis.default.host=" + LOCAL_REDIS_HOST,
            "openjiuwen.service.middleware.redis.default.port=" + LOCAL_REDIS_PORT,
            "openjiuwen.service.middleware.redis.default.database=0",
            "openjiuwen.service.middleware.redis.default.encrypted-password=");

    @AfterEach
    void tearDown() {
        if (localRedisCleanupPrefix != null && isLocalRedisReachable()) {
            deleteRedisKeysByPrefix(localRedisCleanupPrefix);
            localRedisCleanupPrefix = null;
        }
        resetRunnerEnvironment();
    }

    @Test
    @SuppressWarnings("unchecked")
    void springContextRedisRestoresSessionOnRestart() {
        assumeTrue(isLocalRedisReachable(), "Local Redis on 127.0.0.1:6379 is required for this IT");

        String conversationId = "c-spring-redis-it";
        localRedisCleanupPrefix = conversationId + ":";
        deleteRedisKeysByPrefix(localRedisCleanupPrefix);

        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MiddlewareAdapterRegistrar.class);

            Map<String, Object> checkpointerConfig = RunnerConfig.getRunnerConfig().getCheckpointerConfig();
            assertThat(checkpointerConfig.get("type")).isEqualTo("redis");

            MiddlewareAdapterRegistrar registrar = context.getBean(MiddlewareAdapterRegistrar.class);

            resetRunnerEnvironment();
            JiuwenCoreAgentHandler firstHandler = context.getBean(JiuwenCoreAgentHandler.class);
            firstHandler.start();
            QueryResponse first = firstHandler.query(request(conversationId, "a"));
            firstHandler.stop();

            assertThat(countRedisKeys(conversationId + ":")).isGreaterThan(0);

            JiuwenCoreAgentHandler secondHandler = new JiuwenCoreAgentHandler(new SessionEchoAgent(), registrar);
            secondHandler.start();
            QueryResponse second = secondHandler.query(request(conversationId, "b"));
            secondHandler.stop();

            assertThat((Map<String, Object>) first.getResult()).containsEntry("content", "turn1:a");
            assertThat((Map<String, Object>) second.getResult()).containsEntry("content", "turn2:b|prev=a");
        });
    }

    /** Spring test configuration for Redis middleware integration. */
    @Configuration
    static class TestAgentHandlerConfiguration {
        /**
         * Creates the Redis IT agent handler bean.
         *
         * @param registrar registrar
         * @return JiuwenCoreAgentHandler
         */
        @Bean
        JiuwenCoreAgentHandler springItAgentHandler(MiddlewareAdapterRegistrar registrar) {
            return new JiuwenCoreAgentHandler(new SessionEchoAgent(), registrar);
        }
    }

    /** Test agent that echoes session history across turns. */
    public static class SessionEchoAgent {
        /**
         * Streams a reply while persisting conversation history in session state.
         *
         * @param inputs the runner inputs
         * @param session the agent session
         * @param streamModes the requested stream modes
         * @return the output iterator
         */
        @SuppressWarnings("unchecked")
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            Map<String, Object> inputMap = (Map<String, Object>) inputs;
            String query = String.valueOf(inputMap.get("query"));
            Object priorState = session.getState("history");
            List<String> history = priorState instanceof List<?>
                ? new ArrayList<>((List<String>) priorState)
                : new ArrayList<>();
            String reply = "turn" + (history.size() + 1) + ":" + query;
            if (!history.isEmpty()) {
                reply += "|prev=" + String.join(",", history);
            }
            history.add(query);
            session.updateState(Map.of("history", history));
            return List.<Object>of(new OutputSchema("llm_output", 0, Map.of("content", reply))).iterator();
        }
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

    private static boolean isLocalRedisReachable() {
        try (Jedis jedis = new Jedis(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT)) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (Exception ex) {
            return false;
        }
    }

    private static long countRedisKeys(String prefix) {
        try (Jedis jedis = new Jedis(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT)) {
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
    }

    private static void deleteRedisKeysByPrefix(String prefix) {
        try (Jedis jedis = new Jedis(LOCAL_REDIS_HOST, LOCAL_REDIS_PORT)) {
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
