/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.extensions.checkpointer.redis.RedisCheckpointer;
import com.openjiuwen.service.adapters.common.credential.PassthroughCredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import com.openjiuwen.service.spec.spi.RuntimeRedisClient;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/**
 * DefaultMiddlewareAdapterRegistrarTest
 *
 * @since 2026-07-03
 */
class DefaultMiddlewareAdapterRegistrarTest {
    @Test
    void appliesInMemoryCheckpointerConfig() {
        MiddlewareProperties properties = new MiddlewareProperties();
        DefaultMiddlewareAdapterRegistrar registrar = new DefaultMiddlewareAdapterRegistrar(properties,
                new PassthroughCredentialDecryptor(), null);

        RunnerConfig runnerConfig = RunnerConfig.builder().distributedMode(false).build();
        registrar.applyToRunnerConfig(runnerConfig);

        assertThat(runnerConfig.getCheckpointerConfig().get("type")).isEqualTo("in_memory");
        var checkpointer = CheckpointerFactory.create("in_memory", Map.of());
        assertThat(checkpointer).isNotNull();
    }

    @Test
    void appliesRedisCheckpointerConfigWithoutExplicitRegister() {
        MiddlewareProperties properties = new MiddlewareProperties();
        properties.getCheckpointer().setType("redis");
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("127.0.0.1");
        endpoint.setPort(6379);
        endpoint.setEncryptedPassword("");
        properties.getRedis().put("default", endpoint);

        DefaultMiddlewareAdapterRegistrar registrar = new DefaultMiddlewareAdapterRegistrar(properties,
                new PassthroughCredentialDecryptor(), new NoopRuntimeRedisClient());

        RunnerConfig runnerConfig = RunnerConfig.builder().distributedMode(false).build();
        registrar.applyToRunnerConfig(runnerConfig);

        @SuppressWarnings("unchecked")
        Map<String, Object> conf = (Map<String, Object>) runnerConfig.getCheckpointerConfig().get("conf");
        @SuppressWarnings("unchecked")
        Map<String, Object> connection = (Map<String, Object>) conf.get("connection");
        assertThat(connection.get("redis_client")).isNotNull();
        var checkpointer = CheckpointerFactory.create("redis", conf);
        assertThat(checkpointer).isInstanceOf(RedisCheckpointer.class);
    }

    private static final class NoopRuntimeRedisClient implements RuntimeRedisClient {
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
