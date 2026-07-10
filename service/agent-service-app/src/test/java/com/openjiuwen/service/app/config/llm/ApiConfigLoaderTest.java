/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unit tests for {@link ApiConfigLoader}.
 */
class ApiConfigLoaderTest {
    @TempDir
    private Path tempDir;

    @Test
    void load_readsExplicitApiConfig() throws Exception {
        Path file = writeConfig(tempDir.resolve("model.json"), "false");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);

        ApiConfigLoader.ApiConfigValues values = loader.load(file.toString(), false).orElseThrow();

        assertThat(values.provider()).hasValue("OpenAI");
        assertThat(values.apiKey()).hasValue("ENC:key");
        assertThat(values.apiBase()).hasValue("https://llm.internal/v1");
        assertThat(values.modelName()).hasValue("model-x");
        assertThat(values.shouldVerifySsl()).hasValue(false);
    }

    @Test
    void load_representsMissingValuesWithEmptyOptionals() throws Exception {
        Path file = tempDir.resolve("partial.json");
        Files.writeString(file, "{\"API_BASE\":\"https://llm.internal/v1\"}");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);

        ApiConfigLoader.ApiConfigValues values = loader.load(file.toString(), false).orElseThrow();

        assertThat(values.apiBase()).hasValue("https://llm.internal/v1");
        assertThat(values.provider()).isEmpty();
        assertThat(values.apiKey()).isEmpty();
        assertThat(values.modelName()).isEmpty();
        assertThat(values.shouldVerifySsl()).isEmpty();
    }

    @Test
    void load_discoversApiConfigFromParentDirectory() throws Exception {
        Path workingDirectory = Files.createDirectories(tempDir.resolve("a/b"));
        writeConfig(tempDir.resolve(ApiConfigLoader.DEFAULT_FILE_NAME), "true");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(),
            () -> workingDirectory);

        assertThat(loader.load(null, true)).isPresent();
    }

    @Test
    void load_failsWhenExplicitFileDoesNotExist() {
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);

        assertThatThrownBy(() -> loader.load(tempDir.resolve("missing.json").toString(), true))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("openjiuwen.service.llm.config-file");
    }

    @Test
    void load_rejectsInvalidSslVerifyValue() throws Exception {
        Path file = writeConfig(tempDir.resolve("invalid.json"), "not-a-boolean");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);

        assertThatThrownBy(() -> loader.load(file.toString(), false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LLM_SSL_VERIFY");
    }

    private static Path writeConfig(Path file, String sslVerify) throws Exception {
        Files.writeString(file, """
            {
              "API_BASE": "https://llm.internal/v1",
              "API_KEY": "ENC:key",
              "MODEL_PROVIDER": "OpenAI",
              "MODEL_NAME": "model-x",
              "LLM_SSL_VERIFY": "%s"
            }
            """.formatted(sslVerify));
        return file;
    }
}
