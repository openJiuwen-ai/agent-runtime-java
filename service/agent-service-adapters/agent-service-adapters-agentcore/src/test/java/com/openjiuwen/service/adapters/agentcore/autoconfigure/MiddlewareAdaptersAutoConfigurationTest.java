/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.service.adapters.agentcore.middleware.MiddlewareAdapterRegistrar;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;

import redis.clients.jedis.JedisPooled;

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
                    MiddlewareAdaptersAutoConfiguration.class));

    @Test
    void registersMiddlewareAdapterRegistrarAndAppliesRunnerConfig() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(MiddlewareAdapterRegistrar.class);
            Map<String, Object> checkpointerConfig = RunnerConfig.getRunnerConfig().getCheckpointerConfig();
            assertThat(checkpointerConfig.get("type")).isEqualTo("in_memory");
        });
    }

    @Test
    void bindsRedisCheckpointerFromProperties() {
        contextRunner.withPropertyValues("openjiuwen.service.middleware.checkpointer.type=redis",
                "openjiuwen.service.middleware.redis.default.host=redis.local",
                "openjiuwen.service.middleware.redis.default.port=6380",
                "openjiuwen.service.middleware.redis.default.database=0",
                "openjiuwen.service.middleware.redis.default.encrypted-password=").run(context -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> conf = (Map<String, Object>) RunnerConfig.getRunnerConfig()
                            .getCheckpointerConfig().get("conf");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> connection = (Map<String, Object>) conf.get("connection");
                    assertThat(connection.get("url")).asString().contains("redis.local:6380");
                    assertThat(connection.get("redis_client")).isInstanceOf(JedisPooled.class);
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
}
