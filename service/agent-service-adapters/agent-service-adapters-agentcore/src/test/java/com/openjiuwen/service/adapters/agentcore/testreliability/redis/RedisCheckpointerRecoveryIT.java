/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.testreliability.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.Session;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.stream.OutputSchema;
import com.openjiuwen.core.session.stream.StreamMode;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
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
import redis.clients.jedis.exceptions.JedisConnectionException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * TC_R_001 — Redis 连接断开后 Checkpointer 自动恢复验证。
 *
 * <p>测试场景：Redis 网络中断 → 自动恢复 → 数据完整性验证。</p>
 *
 * <table>
 *   <tr><td><b>维度</b></td><td><b>内容</b></td></tr>
 *   <tr><td>场景</td><td>Redis网络中断 → 自动恢复 → 数据完整性验证</td></tr>
 *   <tr><td>前置</td><td>checkpointer.type=redis，Redis正常运行，存在活跃对话</td></tr>
 *   <tr><td>步骤</td><td>正常查询 → Redis断连 → 中断期间查询 → Redis恢复 → 恢复后查询 → reset清理</td></tr>
 *   <tr><td>预期</td><td>中断期不崩溃/非静默失败；恢复后延迟&lt;5s；数据前后一致；reset清理正确</td></tr>
 * </table>
 *
 * <p>Redis 连接信息从 {@code application.yml}（{@code openjiuwen.service.middleware.redis.default.*})读取，
 * Redis 为独立部署（非 Docker）。断连通过构造指向不可达端口的配置模拟。</p>
 *
 * @since 0.1.0
 */
@Tag("system-test")
class RedisCheckpointerRecoveryIT {
    /** 正常 Redis 主机地址。 */
    private static final String REDIS_HOST = "127.0.0.1";

    /** 正常 Redis 端口。 */
    private static final int REDIS_PORT = 6379;

    /** 用于模拟断连的不可达端口。 */
    private static final int UNREACHABLE_PORT = 19999;

    /** 每个测试的 Redis key 清理前缀，tearDown 时自动删除。 */
    private String localRedisCleanupPrefix;

    @BeforeEach
    void ensureRedisReachable() {
        assumeTrue(isRedisReachable(REDIS_HOST, REDIS_PORT),
            "Redis on " + REDIS_HOST + ":" + REDIS_PORT + " must be reachable for TC_R_001");
    }

    @AfterEach
    void tearDown() {
        if (localRedisCleanupPrefix != null) {
            deleteRedisKeysByPrefix(REDIS_HOST, REDIS_PORT, localRedisCleanupPrefix);
            localRedisCleanupPrefix = null;
        }
        resetRunnerEnvironment();
    }

    // ── TC_R_001 主路径：正常查询 → 断连 → 恢复 → reset ──

    /**
     * 完整恢复路径：正常查询写入 checkpoint → Redis 断连期间查询不静默失败 →
     * Redis 恢复后数据一致 → reset 清理后从 turn1 重新开始。
     */
    @Test
    @SuppressWarnings("unchecked")
    void redisDisconnectThenRecoveryRestoresSessionIntegrity() {
        String conversationId = "c-tc-r-001";
        localRedisCleanupPrefix = conversationId + ":";
        deleteRedisKeysByPrefix(REDIS_HOST, REDIS_PORT, localRedisCleanupPrefix);

        MiddlewareProperties correctProps = redisProperties(REDIS_HOST, REDIS_PORT, "");
        CredentialDecryptor decryptor = new PassthroughCredentialDecryptor();
        DefaultMiddlewareAdapterRegistrar registrar =
            new DefaultMiddlewareAdapterRegistrar(correctProps, decryptor,
                runtimeRedisClient(correctProps, decryptor));

        // ── 步骤 1：正常查询 → 数据写入 Redis checkpoint ──
        resetRunnerEnvironment();
        JiuwenCoreAgentHandler handler =
            new JiuwenCoreAgentHandler(new SessionEchoAgent(), registrar);
        handler.start();

        QueryResponse first = handler.query(request(conversationId, "a"));
        assertThat((Map<String, Object>) first.getResult()).containsEntry("content", "turn1:a");
        assertThat(countRedisKeys(REDIS_HOST, REDIS_PORT, conversationId + ":")).isGreaterThan(0);

        handler.stop();

        // ── 步骤 2：Redis 断连 → 中断期间查询 ──
        MiddlewareProperties unreachableProps = redisProperties(REDIS_HOST, UNREACHABLE_PORT, "");
        DefaultMiddlewareAdapterRegistrar unreachableRegistrar =
            new DefaultMiddlewareAdapterRegistrar(unreachableProps, new PassthroughCredentialDecryptor(),
                unreachableRuntimeRedisClient(unreachableProps));

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler unreachableHandler =
            new JiuwenCoreAgentHandler(new SessionEchoAgent(), unreachableRegistrar);
        unreachableHandler.start();

        assertThatThrownBy(() -> unreachableHandler.query(request(conversationId, "during-outage")))
            .as("中断期间查询不应静默失败，应抛出异常")
            .isInstanceOf(Exception.class);

        stopHandlerQuietly(unreachableHandler);

        // ── 步骤 3：Redis 恢复 → 恢复后查询 ──
        long recoveryStart = System.currentTimeMillis();

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler recoveryHandler =
            new JiuwenCoreAgentHandler(new SessionEchoAgent(), registrar);
        recoveryHandler.start();

        long recoveryElapsed = System.currentTimeMillis() - recoveryStart;
        assertThat(recoveryElapsed).isLessThan(5000L);

        QueryResponse second = recoveryHandler.query(request(conversationId, "b"));
        assertThat((Map<String, Object>) second.getResult())
            .containsEntry("content", "turn2:b|prev=a");

        // ── 步骤 4：reset 清理 ──
        recoveryHandler.clearSession(conversationId);
        deleteRedisKeysByPrefix(REDIS_HOST, REDIS_PORT, conversationId + ":");

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler afterResetHandler =
            new JiuwenCoreAgentHandler(new SessionEchoAgent(), registrar);
        afterResetHandler.start();

        QueryResponse afterReset = afterResetHandler.query(request(conversationId, "fresh"));
        assertThat((Map<String, Object>) afterReset.getResult())
            .containsEntry("content", "turn1:fresh");

        afterResetHandler.stop();
    }

    // ── TC_R_001 断连期间不静默失败验证（独立断连测试） ──

    /**
     * 断连期间查询不应静默失败，必须抛出异常而非返回空/默认结果。
     */
    @Test
    void queryDuringRedisOutageDoesNotSilentlyFail() {
        String conversationId = "c-tc-r-001-silent";
        localRedisCleanupPrefix = conversationId + ":";

        MiddlewareProperties unreachableProps = redisProperties(REDIS_HOST, UNREACHABLE_PORT, "");
        DefaultMiddlewareAdapterRegistrar unreachableRegistrar =
            new DefaultMiddlewareAdapterRegistrar(unreachableProps, new PassthroughCredentialDecryptor(),
                unreachableRuntimeRedisClient(unreachableProps));

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler unreachableHandler =
            new JiuwenCoreAgentHandler(new SessionEchoAgent(), unreachableRegistrar);
        unreachableHandler.start();

        assertThatThrownBy(() -> unreachableHandler.query(request(conversationId, "during-outage")))
            .as("中断期间查询不应静默失败，应抛出异常")
            .isInstanceOf(Exception.class);

        stopHandlerQuietly(unreachableHandler);
    }

    // ── TC_R_001 Handler 重启后从 Redis checkpoint 恢复 session ──

    /**
     * 第一轮 Handler 正常查询 → Handler 重启 → 第二轮恢复后数据一致 → reset 清理。
     */
    @Test
    @SuppressWarnings("unchecked")
    void handlerRestartRestoresSessionFromRedisCheckpoint() {
        String conversationId = "c-tc-r-001-restart";
        localRedisCleanupPrefix = conversationId + ":";
        deleteRedisKeysByPrefix(REDIS_HOST, REDIS_PORT, localRedisCleanupPrefix);

        MiddlewareProperties correctProps = redisProperties(REDIS_HOST, REDIS_PORT, "");
        CredentialDecryptor decryptor = new PassthroughCredentialDecryptor();
        DefaultMiddlewareAdapterRegistrar registrar =
            new DefaultMiddlewareAdapterRegistrar(correctProps, decryptor,
                runtimeRedisClient(correctProps, decryptor));

        // ── 步骤 1：第一轮 Handler 正常查询 ──
        resetRunnerEnvironment();
        JiuwenCoreAgentHandler firstHandler =
            new JiuwenCoreAgentHandler(new SessionEchoAgent(), registrar);
        firstHandler.start();

        QueryResponse first = firstHandler.query(request(conversationId, "a"));
        assertThat((Map<String, Object>) first.getResult()).containsEntry("content", "turn1:a");
        assertThat(countRedisKeys(REDIS_HOST, REDIS_PORT, conversationId + ":")).isGreaterThan(0);

        firstHandler.stop();

        // ── 步骤 3：第二轮 Handler 恢复后查询 ──
        resetRunnerEnvironment();
        JiuwenCoreAgentHandler secondHandler =
            new JiuwenCoreAgentHandler(new SessionEchoAgent(), registrar);
        secondHandler.start();

        QueryResponse second = secondHandler.query(request(conversationId, "b"));
        assertThat((Map<String, Object>) second.getResult())
            .containsEntry("content", "turn2:b|prev=a");

        // ── 步骤 4：reset 清理 ──
        secondHandler.clearSession(conversationId);
        deleteRedisKeysByPrefix(REDIS_HOST, REDIS_PORT, conversationId + ":");

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler afterResetHandler =
            new JiuwenCoreAgentHandler(new SessionEchoAgent(), registrar);
        afterResetHandler.start();

        QueryResponse afterReset = afterResetHandler.query(request(conversationId, "fresh"));
        assertThat((Map<String, Object>) afterReset.getResult())
            .containsEntry("content", "turn1:fresh");

        afterResetHandler.stop();
    }

    // ── TC_R_001 同 Handler 连续两轮（数据前后一致验证） ──

    /**
     * 同一 Handler 内连续多轮查询，数据前后一致，reset 清理后从 turn1 重新开始。
     */
    @Test
    @SuppressWarnings("unchecked")
    void sameHandlerMultiTurnThenResetClearsHistory() {
        String conversationId = "c-tc-r-001-same";
        localRedisCleanupPrefix = conversationId + ":";
        deleteRedisKeysByPrefix(REDIS_HOST, REDIS_PORT, localRedisCleanupPrefix);

        MiddlewareProperties correctProps = redisProperties(REDIS_HOST, REDIS_PORT, "");
        CredentialDecryptor decryptor = new PassthroughCredentialDecryptor();
        DefaultMiddlewareAdapterRegistrar registrar =
            new DefaultMiddlewareAdapterRegistrar(correctProps, decryptor,
                runtimeRedisClient(correctProps, decryptor));

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler handler =
            new JiuwenCoreAgentHandler(new SessionEchoAgent(), registrar);
        handler.start();

        // ── 步骤 1：正常查询 ──
        QueryResponse first = handler.query(request(conversationId, "a"));
        assertThat((Map<String, Object>) first.getResult()).containsEntry("content", "turn1:a");
        assertThat(countRedisKeys(REDIS_HOST, REDIS_PORT, conversationId + ":")).isGreaterThan(0);

        // ── 步骤 3：连续查询 ──
        QueryResponse second = handler.query(request(conversationId, "b"));
        assertThat((Map<String, Object>) second.getResult())
            .containsEntry("content", "turn2:b|prev=a");

        // ── 步骤 4：reset 清理 ──
        handler.clearSession(conversationId);
        deleteRedisKeysByPrefix(REDIS_HOST, REDIS_PORT, conversationId + ":");

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler afterResetHandler =
            new JiuwenCoreAgentHandler(new SessionEchoAgent(), registrar);
        afterResetHandler.start();

        QueryResponse afterReset = afterResetHandler.query(request(conversationId, "fresh"));
        assertThat((Map<String, Object>) afterReset.getResult())
            .containsEntry("content", "turn1:fresh");

        afterResetHandler.stop();
    }

    // ── 辅助方法 ──

    /**
     * 从 MiddlewareProperties 创建 RuntimeRedisClient，用于正常连接场景。
     *
     * @param properties 中间件属性配置
     * @param decryptor 凭据解密器
     * @return 正常连接的 RuntimeRedisClient
     */
    private static RuntimeRedisClient runtimeRedisClient(MiddlewareProperties properties,
        CredentialDecryptor decryptor) {
        MiddlewareProperties.RedisEndpoint endpoint = properties.getRedis().get("default");
        String password = decryptor.decrypt(endpoint.getEncryptedPassword(), CredentialSceneType.REDIS_PASSWORD);
        return new JedisPooledRuntimeRedisClient(RedisJedisClientFactory.createPooled(endpoint, password));
    }

    /**
     * 创建指向不可达端口的 RuntimeRedisClient，用于模拟 Redis 网络中断。
     *
     * <p>JedisPooled 在构造时不立即建立连接（懒连接），所以创建本身不会抛异常。
     * 但运行时任何 Redis 操作都会触发 {@code JedisConnectionException}。</p>
     *
     * @param properties 中间件属性配置（指向不可达端口）
     * @return 指向不可达端口的 RuntimeRedisClient
     */
    private static RuntimeRedisClient unreachableRuntimeRedisClient(MiddlewareProperties properties) {
        MiddlewareProperties.RedisEndpoint endpoint = properties.getRedis().get("default");
        return new JedisPooledRuntimeRedisClient(
            RedisJedisClientFactory.createPooled(endpoint, ""));
    }

    /**
     * 构建 Redis checkpointer 配置（与 application.yml 结构一致）。
     *
     * @param host Redis 主机地址
     * @param port Redis 端口
     * @param encryptedPassword 加密密码（空字符串表示无密码）
     * @return 配置好的 MiddlewareProperties
     */
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

    /**
     * 构建测试 ServeRequest。
     *
     * @param conversationId 对话 ID
     * @param content 用户消息内容
     * @return 构建好的 ServeRequest
     */
    private static ServeRequest request(String conversationId, String content) {
        ServeRequest request = new ServeRequest();
        request.setConversationId(conversationId);
        request.setMessages(List.of(Map.of("role", "user", "content", content)));
        request.setUserId("anonymous");
        request.setSpaceId("default");
        return request;
    }

    /**
     * 安全地停止 Handler，忽略停止过程中可能出现的异常。
     * 停止失败不影响测试断言结果，仅需确保资源释放尝试。
     *
     * @param handler 待停止的 Handler
     */
    private static void stopHandlerQuietly(JiuwenCoreAgentHandler handler) {
        try {
            handler.stop();
        } catch (IllegalStateException | UnsupportedOperationException ex) {
            // Handler 可能已处于非法状态或内部资源释放失败，忽略停止异常
            // 不影响测试断言结果，仅需确保资源释放尝试
        }
    }

    /**
     * 重置 Runner 全局状态，确保后续测试的 {@code handler.start()} 能正常执行。
     */
    private static void resetRunnerEnvironment() {
        try {
            Runner.stop();
        } catch (IllegalStateException ex) {
            // Runner.stop() 在 Runner 未启动时抛出 IllegalStateException；
            // Runner 可能未启动或已停止，忽略状态异常
        }
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(null);
        RunnerConfig.setRunnerConfig(null);
        CheckpointerFactory.setDefaultCheckpointer(null);
        resetRunnerStarted();
    }

    /**
     * 重置 {@code JiuwenCoreAgentHandler.RUNNER_STARTED} static 标志，
     * 确保后续测试的 {@code handler.start()} 能真正执行配置注册和 Runner 启动。
     *
     * <p>{@code RUNNER_STARTED} 是 {@code static AtomicBoolean}，在前一个测试将其设为 true 后，
     * 如果某些异常路径未能重置，会导致后续 {@code start()} 直接 return 而跳过配置。</p>
     */
    private static void resetRunnerStarted() {
        try {
            Field field = JiuwenCoreAgentHandler.class.getDeclaredField("RUNNER_STARTED");
            field.setAccessible(true);
            Object fieldValue = field.get(null);
            if (fieldValue instanceof java.util.concurrent.atomic.AtomicBoolean atomicFlag) {
                atomicFlag.set(false);
            }
        } catch (NoSuchFieldException | IllegalAccessException ex) {
            // Field may not exist or be inaccessible in future versions — 非关键路径
        }
    }

    private static boolean isRedisReachable(String host, int port) {
        try (Jedis jedis = new Jedis(host, port, 1000)) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (JedisConnectionException ex) {
            // Redis 连接失败（端口不可达、超时等），不影响测试前置检查
            return false;
        }
    }

    private static long countRedisKeys(String host, int port, String prefix) {
        try (Jedis jedis = new Jedis(host, port)) {
            return scanKeyCount(jedis, prefix);
        } catch (JedisConnectionException ex) {
            // Redis 不可达或连接失败时返回 0，不影响测试逻辑
            return 0L;
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
        } catch (JedisConnectionException ex) {
            // Keys may not exist or Redis may not be reachable — 清理失败不影响测试
        }
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

    // ── 内部类：Session echo agent ──

    /**
     * Session echo agent — mirrors {@code JiuwenCoreAgentHandlerTest.SessionEchoAgent}。
     * Tracks history via session state; reply format: turnN:query|prev=prior_queries.
     */
    public static class SessionEchoAgent {
        /**
         * Echo agent 的 stream 方法，根据输入查询和历史会话状态生成响应。
         *
         * @param inputs 输入参数 Map，包含 query 字段
         * @param session 会话对象，用于跟踪历史状态
         * @param streamModes 流模式列表
         * @return 输出迭代器
         */
        @SuppressWarnings("unchecked")
        public Iterator<Object> stream(Object inputs, Session session, List<StreamMode> streamModes) {
            if (!(inputs instanceof Map<?, ?>)) {
                return List.<Object>of().iterator();
            }
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
}
