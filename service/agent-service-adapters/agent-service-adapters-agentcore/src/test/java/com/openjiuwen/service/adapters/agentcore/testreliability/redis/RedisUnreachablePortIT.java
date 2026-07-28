/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.testreliability.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.core.runner.Runner;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.middleware.DefaultMiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.credential.PassthroughCredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.JedisPooledRuntimeRedisClient;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisConnectionAssembler;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisJedisClientFactory;
import com.openjiuwen.service.spec.dto.ServeRequest;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * TC_R_002 — Redis 服务不可达（错误端口）健康检查与启动降级验证。
 *
 * <p>测试场景：Redis 配置指向不可达端口（6371），系统应检测到连接失败并给出明确提示。</p>
 *
 * <table>
 *   <tr><td><b>维度</b></td><td><b>内容</b></td></tr>
 *   <tr><td>场景</td><td>Redis 配置端口 6371 不可达 → ping 失败 → 启动降级提示</td></tr>
 *   <tr><td>前置</td><td>checkpointer.type=redis，Redis 正常运行在 6379，端口 6371 无 Redis 服务</td></tr>
 *   <tr><td>步骤</td><td>ping 6371 → 预期失败 → 构造错误配置 → Handler 启动 → 验证降级行为</td></tr>
 *   <tr><td>预期</td><td>ping 失败抛 JedisConnectionException；Handler 降级运行不崩溃；无静默失败</td></tr>
 * </table>
 *
 * @since 0.1.0
 */
@Tag("system-test")
class RedisUnreachablePortIT {
    /** 正常 Redis 端口（用于对比验证）。 */
    private static final String REDIS_HOST = "127.0.0.1";
    private static final int REDIS_PORT = 6379;

    /** 不可达端口：6371（无 Redis 服务监听）。 */
    private static final int UNREACHABLE_PORT = 6371;

    @AfterEach
    void tearDown() {
        resetRunnerEnvironment();
    }

    // ── TC_R_002-1：ping 不可达端口应抛出 JedisConnectionException ──

    @Test
    void pingUnreachablePortThrowsJedisConnectionException() {
        assertThatThrownBy(() -> {
            try (Jedis jedis = new Jedis(REDIS_HOST, UNREACHABLE_PORT, 1000)) {
                jedis.ping();
            }
        })
            .as("ping 不可达端口 6371 应抛出连接异常，而非返回 null 或静默失败")
            .isInstanceOf(JedisConnectionException.class);
    }

    // ── TC_R_002-2：RuntimeRedisClient 连接不可达端口时 ping 失败 ──

    @Test
    void runtimeRedisClientPingUnreachablePortFails() {
        MiddlewareProperties unreachableProps = redisProperties(REDIS_HOST, UNREACHABLE_PORT, "");
        MiddlewareProperties.RedisEndpoint endpoint = unreachableProps.getRedis().get("default");

        RuntimeRedisClient client = new JedisPooledRuntimeRedisClient(
            RedisJedisClientFactory.createPooled(endpoint, ""));

        assertThatThrownBy(() -> client.set("tc-r-002:test-key", "test-value"))
            .as("RuntimeRedisClient 连接不可达端口时操作应抛出连接异常")
            .isInstanceOf(JedisConnectionException.class);
    }

    // ── TC_R_002-3：错误端口配置下 Handler 启动后的降级验证 ──

    /**
     * 不可达 Redis 端口配置下查询不应静默失败，应抛出异常。
     * RuntimeRedisClient 必须传入（指向错误端口的真实客户端），
     * 因为 AgentCoreCheckpointerConfigAssembler 要求 redis checkpointer 不能为 null。
     */
    @Test
    void handlerWithUnreachableRedisPortDoesNotSilentlyFail() {
        MiddlewareProperties unreachableProps = redisProperties(REDIS_HOST, UNREACHABLE_PORT, "");
        PassthroughCredentialDecryptor decryptor = new PassthroughCredentialDecryptor();
        RuntimeRedisClient unreachableClient = runtimeRedisClient(unreachableProps, decryptor);
        DefaultMiddlewareAdapterRegistrar registrar =
            new DefaultMiddlewareAdapterRegistrar(unreachableProps, decryptor, unreachableClient);

        resetRunnerEnvironment();
        JiuwenCoreAgentHandler handler =
            new JiuwenCoreAgentHandler(new RedisCheckpointerRecoveryIT.SessionEchoAgent(), registrar);
        handler.start();

        assertThatThrownBy(() -> handler.query(request("c-tc-r-002", "unreachable-query")))
            .as("不可达 Redis 端口配置下查询不应静默失败，应抛出异常")
            .isInstanceOf(Exception.class);

        stopHandlerQuietly(handler);
    }

    // ── TC_R_002-5：RedisConnectionAssembler 对不可达端口的摘要不暴露凭据 ──

    @Test
    void connectionSummaryForUnreachablePortDoesNotExposeCredentials() {
        MiddlewareProperties unreachableProps = redisProperties(REDIS_HOST, UNREACHABLE_PORT, "secret-password");
        MiddlewareProperties.RedisEndpoint endpoint = unreachableProps.getRedis().get("default");

        String summary = RedisConnectionAssembler.safeSummary("default", endpoint);

        assertThat(summary)
            .as("连接摘要不应暴露明文密码")
            .doesNotContain("secret-password");

        assertThat(summary)
            .as("连接摘要应包含端口信息以帮助排查配置错误")
            .contains("6371");

        assertThat(summary)
            .as("连接摘要应标记密码已配置")
            .contains("passwordConfigured=true");
    }

    // ── 辅助方法 ──

    /**
     * 从 MiddlewareProperties 创建 RuntimeRedisClient（即使指向不可达端口也需要传入，
     * 因为 AgentCoreCheckpointerConfigAssembler 要求 redis checkpointer 的 RuntimeRedisClient 不为 null）。
     *
     * @param properties 中间件属性配置
     * @param decryptor 凭据解密器
     * @return RuntimeRedisClient 实例
     */
    private static RuntimeRedisClient runtimeRedisClient(MiddlewareProperties properties,
        CredentialDecryptor decryptor) {
        MiddlewareProperties.RedisEndpoint endpoint = properties.getRedis().get("default");
        String password = decryptor.decrypt(endpoint.getEncryptedPassword(), CredentialSceneType.REDIS_PASSWORD);
        return new JedisPooledRuntimeRedisClient(RedisJedisClientFactory.createPooled(endpoint, password));
    }

    /**
     * 构建 Redis checkpointer 配置。
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
     *
     * @param handler 待停止的 Handler
     */
    private static void stopHandlerQuietly(JiuwenCoreAgentHandler handler) {
        try {
            handler.stop();
        } catch (IllegalStateException | UnsupportedOperationException ex) {
            // Handler 可能已处于非法状态或内部资源释放失败，忽略停止异常
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
    }
}
