/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.core.memory.external.MemoryProvider;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Tests Spring auto-configuration for the governed runtime memory store.
 *
 * @since 0.1.0
 */
class MemoryAdaptersAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(CredentialDecryptorAutoConfiguration.class, MemoryAdaptersAutoConfiguration.class));

    @Test
    void disabledMemoryRegistersNoMemoryStoreBean() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(MemoryStore.class);
            assertThat(context).doesNotHaveBean(MemoryProvider.class);
        });
    }

    @Test
    void enabledMemoryRegistersMemoryStoreWithoutUserId() {
        contextRunner.withPropertyValues(
            "openjiuwen.service.middleware.memory.enabled=true",
            "openjiuwen.service.middleware.memory.provider=mem0",
            "openjiuwen.service.middleware.memory.endpoint=https://mem0.example",
            "openjiuwen.service.middleware.memory.encrypted-api-key=plainkey")
            .run(context -> {
                assertThat(context).hasSingleBean(MemoryStore.class);
                assertThat(context).hasSingleBean(MemoryProvider.class);
                assertThat(context.getBean(MemoryStore.class).isAvailable()).isTrue();
                assertThat(context.getBean(MemoryProvider.class).isAvailable()).isTrue();
            });
    }

    @Test
    void enabledMemoryFailsWhenApiKeyIsMissing() {
        contextRunner.withPropertyValues(
            "openjiuwen.service.middleware.memory.enabled=true",
            "openjiuwen.service.middleware.memory.provider=mem0")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("encrypted-api-key");
            });
    }

    @Test
    void enabledMemoryFailsWhenProviderIsUnsupported() {
        contextRunner.withPropertyValues(
            "openjiuwen.service.middleware.memory.enabled=true",
            "openjiuwen.service.middleware.memory.provider=openviking",
            "openjiuwen.service.middleware.memory.encrypted-api-key=plainkey")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasMessageContaining("Unsupported memory provider");
            });
    }

    @Test
    void invalidMemoryGovernanceConfigurationFailsStartup() {
        contextRunner.withPropertyValues("openjiuwen.service.middleware.memory.timeout-ms=0")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "memory.timeout-ms must be greater than zero");
            });
    }
}
