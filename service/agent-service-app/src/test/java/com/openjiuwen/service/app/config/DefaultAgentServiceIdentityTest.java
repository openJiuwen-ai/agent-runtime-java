/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * DefaultAgentServiceIdentityTest
 *
 * @since 2026-07-03
 */
class DefaultAgentServiceIdentityTest {
    @Test
    void readsSpringApplicationName() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.application.name", "my-agent");

        DefaultAgentServiceIdentity identity = new DefaultAgentServiceIdentity(environment);

        assertThat(identity.getAppName()).isEqualTo("my-agent");
    }

    @Test
    void defaultsWhenPropertyMissing() {
        DefaultAgentServiceIdentity identity = new DefaultAgentServiceIdentity(new MockEnvironment());

        assertThat(identity.getAppName()).isEqualTo("application");
    }
}
