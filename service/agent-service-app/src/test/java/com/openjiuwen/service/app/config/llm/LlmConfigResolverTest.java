/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unit tests for {@link LlmConfigResolver}.
 */
class LlmConfigResolverTest {
    @TempDir
    private Path tempDir;

    @Test
    void resolve_appliesFileValuesDecryptsApiKeyAndCachesResult() throws Exception {
        Path file = tempDir.resolve("apiconfig.json");
        Files.writeString(file, """
            {
              "API_BASE": "https://file.example/v1",
              "API_KEY": "ENC:file-key",
              "MODEL_PROVIDER": "FileProvider",
              "MODEL_NAME": "file-model",
              "LLM_SSL_VERIFY": false
            }
            """);
        LlmProperties properties = new LlmProperties();
        properties.setConfigFile(file.toString());
        AtomicInteger invocationCount = new AtomicInteger();
        AtomicInteger scene = new AtomicInteger();
        CredentialDecryptor decryptor = sceneAwareDecryptor(invocationCount, scene);
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);
        LlmConfigResolver resolver = new LlmConfigResolver(properties, loader, decryptor);

        ResolvedLlmConfig first = resolver.resolveRequired();
        ResolvedLlmConfig second = resolver.resolveRequired();

        assertThat(first).isSameAs(second);
        assertThat(first.getProvider()).isEqualTo("FileProvider");
        assertThat(first.getApiKey()).isEqualTo("plain:file-key");
        assertThat(first.getApiBase()).isEqualTo("https://file.example/v1");
        assertThat(first.getModelName()).isEqualTo("file-model");
        assertThat(first.isSslVerify()).isFalse();
        assertThat(scene.get()).isEqualTo(CredentialSceneType.LLM_API_KEY);
        assertThat(invocationCount.get()).isOne();
    }

    @Test
    void resolve_prefersSpringPropertiesOverFileValues() throws Exception {
        Path file = tempDir.resolve("apiconfig.json");
        Files.writeString(file, """
            {
              "API_BASE": "https://file.example/v1",
              "API_KEY": "file-key",
              "MODEL_PROVIDER": "FileProvider",
              "MODEL_NAME": "file-model",
              "LLM_SSL_VERIFY": false
            }
            """);
        LlmProperties properties = configuredProperties();
        properties.setConfigFile(file.toString());
        properties.setProvider("SpringProvider");
        properties.setSslVerify(true);
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);
        LlmConfigResolver resolver = new LlmConfigResolver(properties, loader, ciphertext -> ciphertext);

        ResolvedLlmConfig config = resolver.resolveRequired();

        assertThat(config.getProvider()).isEqualTo("SpringProvider");
        assertThat(config.getApiKey()).isEqualTo("spring-key");
        assertThat(config.getApiBase()).isEqualTo("https://spring.example/v1");
        assertThat(config.getModelName()).isEqualTo("spring-model");
        assertThat(config.isSslVerify()).isTrue();
    }

    @Test
    void resolve_appliesRuntimeDefaults() {
        LlmConfigResolver resolver = new LlmConfigResolver(new LlmProperties(), new MockEnvironment(),
            ciphertext -> ciphertext);

        ResolvedLlmConfig config = resolver.resolve();

        assertThat(config.getProvider()).isEqualTo("OpenAI");
        assertThat(config.isSslVerify()).isTrue();
        assertThat(config.getTemperature()).isEqualTo(0.6D);
        assertThat(config.getTopP()).isEqualTo(0.8D);
        assertThat(config.getTimeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.getContextWindowLimit()).isEqualTo(10);
        assertThat(config.getMaxIterations()).isEqualTo(5);
    }

    @Test
    void resolveRequired_rejectsMissingOutboundProperties() {
        LlmConfigResolver resolver = new LlmConfigResolver(new LlmProperties(), new MockEnvironment(),
            ciphertext -> ciphertext);

        assertThatThrownBy(resolver::resolveRequired)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("openjiuwen.service.llm.api-key");
    }

    @Test
    void resolvedConfigBuilder_doesNotExposeApiKeyInToString() {
        String representation = ResolvedLlmConfig.builder().apiKey("sensitive-key").toString();

        assertThat(representation).doesNotContain("sensitive-key");
    }

    private static CredentialDecryptor sceneAwareDecryptor(AtomicInteger invocationCount, AtomicInteger scene) {
        return new CredentialDecryptor() {
            @Override
            public String decrypt(String ciphertext) {
                return ciphertext;
            }

            @Override
            public String decrypt(String ciphertext, int sceneType) {
                invocationCount.incrementAndGet();
                scene.set(sceneType);
                return ciphertext.replace("ENC:", "plain:");
            }
        };
    }

    private static LlmProperties configuredProperties() {
        LlmProperties properties = new LlmProperties();
        properties.setApiKey("spring-key");
        properties.setApiBase("https://spring.example/v1");
        properties.setModelName("spring-model");
        return properties;
    }
}
