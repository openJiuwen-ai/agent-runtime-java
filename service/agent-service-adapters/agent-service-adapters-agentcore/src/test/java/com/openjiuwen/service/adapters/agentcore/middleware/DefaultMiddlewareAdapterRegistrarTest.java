/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.middleware;

import com.openjiuwen.core.runner.RunnerConfig;
import com.openjiuwen.core.session.checkpointer.CheckpointerFactory;
import com.openjiuwen.extensions.checkpointer.redis.RedisCheckpointer;
import com.openjiuwen.service.adapters.common.credential.PassthroughCredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMiddlewareAdapterRegistrarTest {

    @Test
    void appliesInMemoryCheckpointerConfig() {
        MiddlewareProperties properties = new MiddlewareProperties();
        DefaultMiddlewareAdapterRegistrar registrar = new DefaultMiddlewareAdapterRegistrar(properties,
                new PassthroughCredentialDecryptor());

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
                new PassthroughCredentialDecryptor());

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
}
