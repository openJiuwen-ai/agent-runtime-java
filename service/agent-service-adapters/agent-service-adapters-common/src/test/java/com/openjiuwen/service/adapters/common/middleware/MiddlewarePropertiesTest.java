/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

/**
 * MiddlewarePropertiesTest
 *
 * @since 2026-07-03
 */
class MiddlewarePropertiesTest {
    @Test
    void bindsCheckpointerAndPlaceholderCapabilitiesTogether() {
        Map<String, String> source = Map.of("openjiuwen.service.middleware.checkpointer.type", "redis",
                "openjiuwen.service.middleware.session-store.type", "none",
                "openjiuwen.service.middleware.object-storage.type", "none",
                "openjiuwen.service.middleware.vector-store.type", "none",
                "openjiuwen.service.middleware.redis.default.host", "redis.local",
                "openjiuwen.service.middleware.redis.default.encrypted-password", "pwd");

        MiddlewareProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("openjiuwen.service.middleware", Bindable.of(MiddlewareProperties.class))
                .orElseGet(MiddlewareProperties::new);

        assertThat(properties.getCheckpointer().getType()).isEqualTo("redis");
        assertThat(properties.getSessionStore().getType()).isEqualTo("none");
        assertThat(properties.getObjectStorage().getType()).isEqualTo("none");
        assertThat(properties.getVectorStore().getType()).isEqualTo("none");
        assertThat(properties.getRedis().get("default").getHost()).isEqualTo("redis.local");
        assertThat(properties.getRedis().get("default").getEncryptedPassword()).isEqualTo("pwd");
    }

    @Test
    void bindsRedisEndpointTypeAndClusterNodes() {
        Map<String, Object> source = Map.of("openjiuwen.service.middleware.checkpointer.type", "redis",
                "openjiuwen.service.middleware.checkpointer.redis-ref", "cluster",
                "openjiuwen.service.middleware.redis.cluster.type", "cluster",
                "openjiuwen.service.middleware.redis.cluster.nodes[0]", "10.10.1.11:6379",
                "openjiuwen.service.middleware.redis.cluster.nodes[1]", "10.10.1.12:6379",
                "openjiuwen.service.middleware.redis.cluster.database", "2");

        MiddlewareProperties properties = new Binder(new MapConfigurationPropertySource(source))
                .bind("openjiuwen.service.middleware", Bindable.of(MiddlewareProperties.class))
                .orElseGet(MiddlewareProperties::new);

        MiddlewareProperties.RedisEndpoint endpoint = properties.getRedis().get("cluster");
        assertThat(endpoint.getType()).isEqualTo("cluster");
        assertThat(endpoint.getNodes()).containsExactly("10.10.1.11:6379", "10.10.1.12:6379");
        assertThat(endpoint.getDatabase()).isEqualTo(2);
    }

    @Test
    void defaultsRedisEndpointTypeToStandaloneAndNodesToEmptyList() {
        MiddlewareProperties.RedisEndpoint endpoint = new MiddlewareProperties.RedisEndpoint();

        assertThat(endpoint.getType()).isEqualTo("standalone");
        assertThat(endpoint.getNodes()).isEqualTo(List.of());
    }
}
