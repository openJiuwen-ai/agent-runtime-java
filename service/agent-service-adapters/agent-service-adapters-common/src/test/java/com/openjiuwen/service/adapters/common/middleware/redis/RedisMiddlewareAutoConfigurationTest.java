/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests Redis middleware auto-configuration behavior.
 *
 * @since 0.1.0
 */
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
    void createsStandaloneClientWhenTypeIsOmitted() {
        contextRunner
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                        "openjiuwen.service.middleware.redis.default.host=redis.local")
                .run(context -> assertThat(context.getBean(RuntimeRedisClient.class))
                        .isInstanceOf(JedisPooledRuntimeRedisClient.class));
    }

    @Test
    void decryptsPasswordWithRedisScene() {
        AtomicInteger scene = new AtomicInteger(CredentialSceneType.UNKNOWN);
        contextRunner.withBean(CredentialDecryptor.class, () -> sceneAwareDecryptor(scene))
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                        "openjiuwen.service.middleware.redis.default.host=redis.local",
                        "openjiuwen.service.middleware.redis.default.encrypted-password=ENC(redis-password)")
                .run(context -> {
                    assertThat(context).hasSingleBean(RuntimeRedisClient.class);
                    assertThat(scene).hasValue(CredentialSceneType.REDIS_PASSWORD);
                });
    }

    @Test
    void createsClusterRuntimeRedisClientAndIgnoresDatabase() {
        contextRunner.withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                "openjiuwen.service.middleware.checkpointer.redis-ref=cluster",
                "openjiuwen.service.middleware.redis.cluster.type=cluster",
                "openjiuwen.service.middleware.redis.cluster.nodes[0]=10.10.1.11:6379",
                "openjiuwen.service.middleware.redis.cluster.nodes[1]=10.10.1.12:6379",
                "openjiuwen.service.middleware.redis.cluster.database=2",
                "openjiuwen.service.middleware.redis.cluster.encrypted-password=ENC(secret)").run(context -> {
                    assertThat(context).hasSingleBean(RuntimeRedisClient.class);
                    assertThat(context.getBean(RuntimeRedisClient.class))
                            .isInstanceOf(JedisClusterRuntimeRedisClient.class);
                    assertThat(RedisDatasourceDiagnostics.diagnosticMessage(
                            context.getBean(
                                    com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties.class),
                            context.getBean(RuntimeRedisClient.class)))
                            .contains("redis-ref=cluster", "endpoint-type=cluster",
                                    "RuntimeRedisClient=JedisClusterRuntimeRedisClient", "ttl-seconds=604800",
                                    "databaseIgnored=2")
                            .doesNotContain("ENC(secret)", "secret");
                });
    }

    @Test
    void backsOffWhenCustomRuntimeRedisClientIsProvided() {
        contextRunner.withUserConfiguration(CustomRedisClientConfiguration.class)
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                        "openjiuwen.service.middleware.redis.default.host=redis.local",
                        "openjiuwen.service.middleware.redis.default.encrypted-password=ENC(custom-secret)")
                .run(context -> {
                    assertThat(context).hasSingleBean(RuntimeRedisClient.class);
                    assertThat(context.getBean(RuntimeRedisClient.class))
                            .isSameAs(CustomRedisClientConfiguration.CUSTOM_CLIENT);
                    assertThat(RedisDatasourceDiagnostics.diagnosticMessage(
                            context.getBean(
                                    com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties.class),
                            context.getBean(RuntimeRedisClient.class)))
                            .contains("RuntimeRedisClient=$Proxy", "endpoint-type=standalone")
                            .doesNotContain("ENC(custom-secret)", "custom-secret");
                });
    }

    @Test
    void failsFastWhenClusterNodesAreMissing() {
        contextRunner
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                        "openjiuwen.service.middleware.redis.default.type=cluster")
                .run(context -> assertThat(context.getStartupFailure()).hasMessageContaining("nodes"));
    }

    @Test
    void failsFastWhenStandaloneHostIsMissing() {
        AtomicInteger decryptCalls = new AtomicInteger();
        contextRunner.withBean(CredentialDecryptor.class, () -> countingDecryptor(decryptCalls))
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                        "openjiuwen.service.middleware.redis.default.type=standalone",
                        "openjiuwen.service.middleware.redis.default.encrypted-password=ENC(secret)")
                .run(context -> {
                    assertThat(context.getStartupFailure()).hasRootCauseMessage(
                            "openjiuwen.service.middleware.redis.default.host is required when type=standalone");
                    assertThat(decryptCalls).hasValue(0);
                });
    }

    @Test
    void failsFastWhenStandaloneHostIsBlank() {
        contextRunner
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                        "openjiuwen.service.middleware.redis.default.host=   ")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage(
                        "openjiuwen.service.middleware.redis.default.host is required when type=standalone"));
    }

    @Test
    void failsFastWhenCheckpointerTtlIsNotPositive() {
        contextRunner
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                        "openjiuwen.service.middleware.checkpointer.ttl-seconds=0",
                        "openjiuwen.service.middleware.redis.default.host=redis.local")
                .run(context -> assertThat(context.getStartupFailure()).hasRootCauseMessage(
                        "openjiuwen.service.middleware.checkpointer.ttl-seconds must be greater than 0"));
    }

    @Test
    void doesNotCreateRedisClientWhenCheckpointerTypeIsMysql() {
        contextRunner.withPropertyValues("openjiuwen.service.middleware.checkpointer.type=mysql",
                "openjiuwen.service.middleware.redis.default.host=redis.local")
                .run(context -> assertThat(context).doesNotHaveBean(RuntimeRedisClient.class));
    }

    @Test
    void doesNotCreateRedisClientWhenCheckpointerTypeIsPersistence() {
        contextRunner.withPropertyValues("openjiuwen.service.middleware.checkpointer.type=persistence")
                .run(context -> assertThat(context).doesNotHaveBean(RuntimeRedisClient.class));
    }

    @Test
    void doesNotCreateRedisClientWhenCheckpointerTypeIsBlank() {
        contextRunner.withPropertyValues("openjiuwen.service.middleware.checkpointer.type=")
                .run(context -> assertThat(context).doesNotHaveBean(RuntimeRedisClient.class));
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

    private static CredentialDecryptor sceneAwareDecryptor(AtomicInteger scene) {
        return new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return "redis-password";
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                scene.set(sceneType);
                return "redis-password";
            }
        };
    }

    private static CredentialDecryptor countingDecryptor(AtomicInteger calls) {
        return new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                calls.incrementAndGet();
                return "redis-password";
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                calls.incrementAndGet();
                return "redis-password";
            }
        };
    }
}
