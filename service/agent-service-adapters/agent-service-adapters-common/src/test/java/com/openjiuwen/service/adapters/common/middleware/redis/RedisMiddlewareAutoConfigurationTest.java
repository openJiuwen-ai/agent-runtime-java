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

import java.util.List;

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
                    assertThat(context.getBean(RuntimeRedisClient.class)).isInstanceOf(CustomRuntimeRedisClient.class);
                });
    }

    @Test
    void doesNotCreateRedisClientWhenCheckpointerIsNotRedis() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(RuntimeRedisClient.class));
    }

    @Configuration
    static class CustomRedisClientConfiguration {
        @Bean
        RuntimeRedisClient runtimeRedisClient() {
            return new CustomRuntimeRedisClient();
        }
    }

    private static final class CustomRuntimeRedisClient implements RuntimeRedisClient {
        @Override
        public Object get(String key) {
            return null;
        }

        @Override
        public byte[] get(byte[] key) {
            return null;
        }

        @Override
        public String set(String key, String value) {
            return "OK";
        }

        @Override
        public String set(String key, byte[] value) {
            return "OK";
        }

        @Override
        public String set(byte[] key, byte[] value) {
            return "OK";
        }

        @Override
        public String setex(String key, long seconds, String value) {
            return "OK";
        }

        @Override
        public String setex(byte[] key, long seconds, byte[] value) {
            return "OK";
        }

        @Override
        public long setnx(String key, String value) {
            return 0;
        }

        @Override
        public long setnx(byte[] key, byte[] value) {
            return 0;
        }

        @Override
        public long del(String... keys) {
            return 0;
        }

        @Override
        public long del(byte[]... keys) {
            return 0;
        }

        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public boolean exists(byte[] key) {
            return false;
        }

        @Override
        public long expire(String key, long seconds) {
            return 0;
        }

        @Override
        public long expire(byte[] key, long seconds) {
            return 0;
        }

        @Override
        public List<Object> mget(String... keys) {
            return List.of();
        }

        @Override
        public List<String> scanIter(String pattern) {
            return List.of();
        }
    }
}
