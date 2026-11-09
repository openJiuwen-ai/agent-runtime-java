/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.openjiuwen.core.foundation.store.BaseKVStore;
import com.openjiuwen.core.foundation.store.kv.InMemoryKVStore;
import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.core.session.checkpointer.PersistenceCheckpointer;
import com.openjiuwen.service.adapters.agentcore.middleware.CheckpointKVStoreProvider;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.adapters.common.middleware.redis.RedisMiddlewareAutoConfiguration;
import com.openjiuwen.service.adapters.common.middleware.redis.UnifiedJedisRuntimeRedisClient;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * MiddlewareAdaptersAutoConfigurationTest
 *
 * @since 2026-07-03
 */
class MiddlewareAdaptersAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CredentialDecryptorAutoConfiguration.class,
                    RedisMiddlewareAutoConfiguration.class, MiddlewareAdaptersAutoConfiguration.class));

    @Test
    void registersMiddlewareAdapterRegistrarAndAppliesRunnerConfig() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MiddlewareAdapterRegistrar.class);
            CheckpointerConfig checkpointerConfig = RunnerConfig.getRunnerConfig().getCheckpointerConfig();
            assertThat(checkpointerConfig.getType()).isEqualTo("in_memory");
        });
    }

    @Test
    void bindsRedisCheckpointerFromProperties() {
        contextRunner.withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                "openjiuwen.service.middleware.redis.default.host=redis.local",
                "openjiuwen.service.middleware.redis.default.port=6380",
                "openjiuwen.service.middleware.redis.default.database=0",
                "openjiuwen.service.middleware.redis.default.encrypted-password=").run(context -> {
                    Map<String, Object> conf = RunnerConfig.getRunnerConfig().getCheckpointerConfig().getConf();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> connection = (Map<String, Object>) conf.get("connection");
                    assertThat(connection.get("url")).asString().contains("redis.local:6380");
                    RuntimeRedisClient redisClient = context.getBean(RuntimeRedisClient.class);
                    assertThat(redisClient).isInstanceOf(UnifiedJedisRuntimeRedisClient.class);
                    if (redisClient instanceof UnifiedJedisRuntimeRedisClient unified) {
                        assertThat(connection.get("redis_client")).isSameAs(unified.jedisDelegate());
                    }
                });
    }

    @Test
    void usesCustomCredentialDecryptorBeanWhenProvided() {
        contextRunner.withUserConfiguration(CustomDecryptorConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(CredentialDecryptor.class);
            assertThat(context.getBean(CredentialDecryptor.class).decrypt("ENC")).isEqualTo("decrypted");
        });
    }

    @Configuration
    static class CustomDecryptorConfiguration {
        @Bean
        CredentialDecryptor credentialDecryptor() {
            return ciphertext -> "decrypted";
        }
    }

    @Test
    void extensionProvidesExactStoreAndReusesItBeforeRunnerStart() {
        InMemoryKVStore store = new InMemoryKVStore();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        contextRunner
                .withPropertyValues("openjiuwen.service.middleware.checkpointer.type=custom",
                        "openjiuwen.service.middleware.checkpointer.connection-ref=primary")
                .withBean(CheckpointKVStoreProvider.class, () -> new CheckpointKVStoreProvider() {
                    @Override
                    public String type() {
                        return "custom";
                    }

                    @Override
                    public BaseKVStore getStore(String ref) {
                        assertThat(ref).isEqualTo("primary");
                        calls.incrementAndGet();
                        return store;
                    }
                }).run(context -> {
                    assertThat(context).hasNotFailed();
                    var registrar = context.getBean(MiddlewareAdapterRegistrar.class);
                    registrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig());
                    registrar.applyToRunnerConfig(RunnerConfig.getRunnerConfig());
                    var config = RunnerConfig.getRunnerConfig().getCheckpointerConfig();
                    assertThat(config.getType()).isEqualTo("persistence");
                    assertThat(config.getConf().get("kv_store")).isSameAs(store);
                    var checkpointer = assertInstanceOf(PersistenceCheckpointer.class,
                            CheckpointerFactory.create(config));
                    assertThat(checkpointer.kvStore()).isSameAs(store);
                    assertThat(calls).hasValue(1);
                });
    }

    @Test
    void extensionRejectsMissingDuplicateAndNullStoreProviders() {
        var configured = contextRunner.withPropertyValues("openjiuwen.service.middleware.checkpointer.type=custom");
        configured.run(context -> assertThat(context).hasFailed());
        configured.withBean("first", CheckpointKVStoreProvider.class, () -> provider(new InMemoryKVStore()))
                .withBean("second", CheckpointKVStoreProvider.class, () -> provider(new InMemoryKVStore()))
                .run(context -> assertThat(context).hasFailed());
        configured.withBean(CheckpointKVStoreProvider.class, () -> provider(null))
                .run(context -> assertThat(context).hasFailed());
    }

    private static CheckpointKVStoreProvider provider(BaseKVStore store) {
        return new CheckpointKVStoreProvider() {
            @Override
            public String type() {
                return "custom";
            }

            @Override
            public BaseKVStore getStore(String ref) {
                return store;
            }
        };
    }
}
