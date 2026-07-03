/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.common.middleware;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

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
}
