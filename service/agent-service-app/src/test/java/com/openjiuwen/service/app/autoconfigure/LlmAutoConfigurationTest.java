/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.LlmProperties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

/**
 * Auto-configuration tests for reusable LLM configuration.
 */
class LlmAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(CredentialDecryptorAutoConfiguration.class, LlmAutoConfiguration.class));

    @Test
    void autoConfiguration_bindsServicePrefixAndRegistersResolver() {
        contextRunner.withPropertyValues("openjiuwen.service.llm.provider=TestProvider",
            "openjiuwen.service.llm.api-key=test-key", "openjiuwen.service.llm.api-base=mirror://test",
            "openjiuwen.service.llm.model-name=test-model", "openjiuwen.service.llm.auto-discover=false",
            "openjiuwen.service.llm.ssl-verify=false", "openjiuwen.service.llm.system-prompt=Test prompt",
            "openjiuwen.service.llm.temperature=0.3", "openjiuwen.service.llm.top-p=0.7",
            "openjiuwen.service.llm.timeout=15s", "openjiuwen.service.llm.context-window-limit=8",
            "openjiuwen.service.llm.max-iterations=4")
            .run(context -> {
                assertThat(context).hasSingleBean(LlmProperties.class);
                assertThat(context).hasSingleBean(LlmConfigResolver.class);
                var config = context.getBean(LlmConfigResolver.class).resolveRequired();
                assertThat(config.getProvider()).isEqualTo("TestProvider");
                assertThat(config.isVerifySsl()).isFalse();
                assertThat(config.getSystemPrompt()).isEqualTo("Test prompt");
                assertThat(config.getTemperature()).isEqualTo(0.3D);
                assertThat(config.getTopP()).isEqualTo(0.7D);
                assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(15));
                assertThat(config.getContextWindowLimit()).isEqualTo(8);
                assertThat(config.getMaxIterations()).isEqualTo(4);
            });
    }

    @Test
    void autoConfiguration_allowsApplicationsWithoutLlmCredentials() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(LlmConfigResolver.class);
        });
    }

    @Test
    void autoConfiguration_doesNotBindRemovedDemoPrefix() {
        contextRunner.withPropertyValues("openjiuwen.demo.llm.api-key=legacy-key",
            "openjiuwen.demo.llm.api-base=mirror://legacy", "openjiuwen.demo.llm.model-name=legacy-model")
            .run(context -> assertThat(context.getBean(LlmConfigResolver.class).resolve().getApiKey()).isEmpty());
    }
}
