/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * RedisConnectionAssemblerTest
 *
 * @since 2026-07-03
 */
class RedisConnectionAssemblerTest {
    @Test
    void buildsRedisUrlWithDecryptedPassword() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("redis.example");
        endpoint.setPort(6380);
        endpoint.setDatabase(2);

        assertThat(RedisConnectionAssembler.buildRedisUrl(endpoint, "plain-pass")).isEqualTo(
            "redis://:plain-pass@redis.example:6380/2");
    }

    @Test
    void buildsConnectionMapFromProperties() {
        MiddlewareProperties properties = new MiddlewareProperties();
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();
        endpoint.setHost("redis.local");
        endpoint.setEncryptedPassword("ENC");
        properties.getRedis().put("default", endpoint);

        CredentialDecryptor decryptor = ciphertext -> "secret";

        Map<String, Object> connection = RedisConnectionAssembler.buildConnectionMap(properties, "default", decryptor);
        assertThat(connection.get("url")).asString().contains("redis.local");
        assertThat(connection.get("url")).asString().contains("secret");
    }

    @Test
    void resolveEndpointRequiresDefinition() {
        MiddlewareProperties properties = new MiddlewareProperties();
        assertThatThrownBy(() -> RedisConnectionAssembler.resolveEndpoint(properties, "default")).isInstanceOf(
            IllegalArgumentException.class).hasMessageContaining("redis.default");
    }
}
