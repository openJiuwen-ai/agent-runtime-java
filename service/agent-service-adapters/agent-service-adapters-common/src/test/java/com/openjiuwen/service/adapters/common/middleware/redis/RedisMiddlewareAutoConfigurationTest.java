/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Proxy;

class RedisMiddlewareAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(CredentialDecryptorAutoConfiguration.class, RedisMiddlewareAutoConfiguration.class));

    @Test
    void createsJedisPooledRuntimeRedisClientWhenRedisCheckpointerEnabled() {
        contextRunner.withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                "openjiuwen.service.middleware.redis.default.host=redis.local",
                "openjiuwen.service.middleware.redis.default.port=6380").run(context -> {
                    assertThat(context).hasSingleBean(RuntimeRedisClient.class);
                    assertThat(context.getBean(RuntimeRedisClient.class))
                            .isInstanceOf(JedisPooledRuntimeRedisClient.class);
                });
    }

    @Test
    void backsOffWhenCustomRuntimeRedisClientIsProvided() {
        contextRunner.withUserConfiguration(CustomRedisClientConfiguration.class)
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                        "openjiuwen.service.middleware.redis.default.host=redis.local")
                .run(context -> {
                    assertThat(context).hasSingleBean(RuntimeRedisClient.class);
                    assertThat(context.getBean(RuntimeRedisClient.class))
                            .isSameAs(CustomRedisClientConfiguration.CUSTOM_CLIENT);
                });
    }

    @Test
    void doesNotCreateRedisClientWhenCheckpointerIsNotRedis() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(RuntimeRedisClient.class));
    }

    @Configuration
    static class CustomRedisClientConfiguration {
        static final RuntimeRedisClient CUSTOM_CLIENT = runtimeRedisClientProxy();

        @Bean
        RuntimeRedisClient runtimeRedisClient() {
            return CUSTOM_CLIENT;
        }
    }

    private static RuntimeRedisClient runtimeRedisClientProxy() {
        return RuntimeRedisClient.class.cast(Proxy.newProxyInstance(RuntimeRedisClient.class.getClassLoader(),
                new Class<?>[]{RuntimeRedisClient.class}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "CustomRuntimeRedisClient";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return null;
                }));
    }
}
