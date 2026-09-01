/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisConnectionAssembler;
import com.openjiuwen.service.adapters.common.middleware.redis.JedisClusterRuntimeRedisClient;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisDatasourceDiagnostics;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisMiddlewareAutoConfiguration;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Map;

/**
 * Spring integration coverage for Redis Cluster middleware wiring.
 *
 * @since 0.1.0
 */
@Tag("system-test")
class RedisClusterMiddlewareSpringIT {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CredentialDecryptorAutoConfiguration.class,
                    RedisMiddlewareAutoConfiguration.class, MiddlewareAdaptersAutoConfiguration.class));

    @AfterEach
    void tearDown() {
        RunnerConfig.getRunnerConfig().setCheckpointerConfig(null);
        RunnerConfig.setRunnerConfig(null);
        CheckpointerFactory.setDefaultCheckpointer(null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void clusterClientFeedsAgentCoreAndIgnoresDatabase() {
        contextRunner
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                        "openjiuwen.service.middleware.checkpointer.redis-ref=cluster",
                        "openjiuwen.service.middleware.redis.cluster.type=cluster",
                        "openjiuwen.service.middleware.redis.cluster.nodes[0]=10.10.1.11:6379",
                        "openjiuwen.service.middleware.redis.cluster.nodes[1]=10.10.1.12:6379",
                        "openjiuwen.service.middleware.redis.cluster.database=2",
                        "openjiuwen.service.middleware.redis.cluster.encrypted-password=ENC(cluster-secret)")
                .run(context -> {
                    assertThat(context).hasSingleBean(MiddlewareAdapterRegistrar.class);
                    assertThat(context.getBean(RuntimeRedisClient.class))
                            .isInstanceOf(JedisClusterRuntimeRedisClient.class);

                    Map<String, Object> checkpointerConfig = RunnerConfig.getRunnerConfig().getCheckpointerConfig();
                    assertThat(checkpointerConfig.get("type")).isEqualTo("redis");

                    Map<String, Object> conf = (Map<String, Object>) checkpointerConfig.get("conf");
                    Map<String, Object> connection = (Map<String, Object>) conf.get("connection");
                    assertThat(connection.get("redis_client")).isSameAs(context.getBean(RuntimeRedisClient.class));
                    assertThat(context).hasSingleBean(RedisDatasourceDiagnostics.class);
                    MiddlewareProperties properties = context.getBean(MiddlewareProperties.class);
                    assertThat(RedisConnectionAssembler.safeSummary("cluster", properties.getRedis().get("cluster")))
                            .contains("type=cluster", "databaseIgnored=2")
                            .doesNotContain("ENC(cluster-secret)", "cluster-secret");
                });
    }
}
