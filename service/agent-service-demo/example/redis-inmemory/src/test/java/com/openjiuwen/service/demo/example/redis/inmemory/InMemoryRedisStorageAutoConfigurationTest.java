/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.redis.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.adapters.common.middleware.redis.JedisPooledRuntimeRedisClient;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisMiddlewareAutoConfiguration;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests plugin takeover and rollback of the runtime Redis client.
 *
 * @since 0.1.3
 */
class InMemoryRedisStorageAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(InMemoryRedisStorageAutoConfiguration.class,
                    CredentialDecryptorAutoConfiguration.class, RedisMiddlewareAutoConfiguration.class));

    private final ApplicationContextRunner contextRunnerWithoutPlugin = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CredentialDecryptorAutoConfiguration.class,
                    RedisMiddlewareAutoConfiguration.class));

    @Test
    void pluginTakesOverWhenNativeClientWouldAlsoBeCreated() {
        contextRunner.withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                "openjiuwen.service.middleware.redis.default.host=redis.local").run(context -> {
                    assertThat(context).hasSingleBean(RuntimeRedisClient.class);
                    assertThat(context.getBean(RuntimeRedisClient.class))
                            .isInstanceOf(InMemoryRuntimeRedisClient.class);
                });
    }

    @Test
    void pluginProvidesClientEvenWhenMiddlewareIsDisabled() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(RuntimeRedisClient.class);
            assertThat(context.getBean(RuntimeRedisClient.class)).isInstanceOf(InMemoryRuntimeRedisClient.class);
        });
    }

    @Test
    void nativeClientReturnsWhenPluginIsAbsent() {
        // 模拟移除插件 jar：插件自动配置不在装配清单中，原生实现按既有条件装配回归。
        contextRunnerWithoutPlugin.withPropertyValues(
                "openjiuwen.service.middleware.checkpointer.type=redis",
                "openjiuwen.service.middleware.redis.default.host=redis.local").run(context -> {
                    assertThat(context).hasSingleBean(RuntimeRedisClient.class);
                    assertThat(context.getBean(RuntimeRedisClient.class))
                            .isInstanceOf(JedisPooledRuntimeRedisClient.class);
                });
    }
}
