/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptorAutoConfiguration;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.LlmProperties;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Auto-configuration tests for reusable LLM configuration.
 */
class LlmAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(CredentialDecryptorAutoConfiguration.class, LlmAutoConfiguration.class));

    @TempDir
    private Path tempDir;

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
                assertThat(config.getApiKey()).isEqualTo("test-key");
                assertThat(config.getApiBase()).isEqualTo("mirror://test");
                assertThat(config.getModelName()).isEqualTo("test-model");
                assertThat(config.isSslVerify()).isFalse();
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

    @Test
    void autoConfiguration_usesCustomCredentialDecryptorAndLlmScene() {
        contextRunner.withUserConfiguration(CustomDecryptorConfiguration.class)
            .withPropertyValues("openjiuwen.service.llm.api-key=encrypted-value",
                "openjiuwen.service.llm.api-base=mirror://configured", "openjiuwen.service.llm.model-name=model-name",
                "openjiuwen.service.llm.auto-discover=false")
            .run(context -> {
                RecordingCredentialDecryptor decryptor = context.getBean(RecordingCredentialDecryptor.class);

                String resolvedCredential = context.getBean(LlmConfigResolver.class).resolveRequired().getApiKey();

                assertThat(context).hasSingleBean(CredentialDecryptor.class);
                assertThat(context.getBean(CredentialDecryptor.class)).isSameAs(decryptor);
                assertThat(resolvedCredential).isEqualTo("decrypted-value");
                assertThat(decryptor.getCiphertext()).isEqualTo("encrypted-value");
                assertThat(decryptor.getSceneType()).isEqualTo(CredentialSceneType.LLM_API_KEY);
            });
    }

    @Tag("smoke")
    @Test
    void autoConfiguration_loadsAndDecryptsApiKeyFromConfiguredFile() throws Exception {
        Path configFile = tempDir.resolve("llm-config.json");
        Files.writeString(configFile, """
            {
              "API_KEY": "file-encrypted-value",
              "API_BASE": "mirror://file",
              "MODEL_NAME": "file-model"
            }
            """);

        contextRunner.withUserConfiguration(CustomDecryptorConfiguration.class)
            .withPropertyValues("openjiuwen.service.llm.config-file=" + configFile,
                "openjiuwen.service.llm.auto-discover=false")
            .run(context -> {
                RecordingCredentialDecryptor decryptor = context.getBean(RecordingCredentialDecryptor.class);

                String resolvedCredential = context.getBean(LlmConfigResolver.class).resolveRequired().getApiKey();

                assertThat(resolvedCredential).isEqualTo("decrypted-value");
                assertThat(decryptor.getCiphertext()).isEqualTo("file-encrypted-value");
                assertThat(decryptor.getSceneType()).isEqualTo(CredentialSceneType.LLM_API_KEY);
            });
    }

    @Test
    void autoConfiguration_propagatesDecryptionFailureDuringAgentCreation() {
        contextRunner.withUserConfiguration(FailingAgentConfiguration.class)
            .withPropertyValues("openjiuwen.service.llm.api-key=encrypted-value",
                "openjiuwen.service.llm.api-base=mirror://configured", "openjiuwen.service.llm.model-name=model-name",
                "openjiuwen.service.llm.auto-discover=false")
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("credential decryption failed");
            });
    }

    @Test
    void autoConfiguration_allowsCustomLlmConfigResolverOverride() {
        LlmConfigResolver customResolver = new LlmConfigResolver(new LlmProperties(), new MockEnvironment(),
            ciphertext -> ciphertext);

        contextRunner.withBean(LlmConfigResolver.class, () -> customResolver).run(context -> {
            assertThat(context).hasSingleBean(LlmConfigResolver.class);
            assertThat(context.getBean(LlmConfigResolver.class)).isSameAs(customResolver);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomDecryptorConfiguration {
        @Bean
        RecordingCredentialDecryptor credentialDecryptor() {
            return new RecordingCredentialDecryptor();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingAgentConfiguration {
        @Bean
        CredentialDecryptor credentialDecryptor() {
            return ciphertext -> {
                throw new IllegalStateException("credential decryption failed");
            };
        }

        @Bean
        String outboundAgent(LlmConfigResolver resolver) {
            resolver.resolveRequired();
            return "outbound-agent";
        }
    }

    static final class RecordingCredentialDecryptor implements CredentialDecryptor {
        private String ciphertext;

        private int sceneType;

        @Override
        public String decrypt(String encryptedValue) {
            return decrypt(encryptedValue, CredentialSceneType.UNKNOWN);
        }

        @Override
        public String decrypt(String encryptedValue, int credentialSceneType) {
            ciphertext = encryptedValue;
            sceneType = credentialSceneType;
            return "decrypted-value";
        }

        String getCiphertext() {
            return ciphertext;
        }

        int getSceneType() {
            return sceneType;
        }
    }
}
