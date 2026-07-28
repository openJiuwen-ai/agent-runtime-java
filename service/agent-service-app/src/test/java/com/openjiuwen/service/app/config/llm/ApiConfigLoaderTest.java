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
    void load_fileAtSixthParentLevel_discoversFile() throws Exception {
        Path configDirectory = tempDir.resolve("root");
        Path workingDirectory = Files.createDirectories(configDirectory.resolve("1/2/3/4/5/6"));
        writeConfig(configDirectory.resolve(ApiConfigLoader.DEFAULT_FILE_NAME), "SixthLevelProvider", "true");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(),
            () -> workingDirectory);

        ApiConfigLoader.ApiConfigValues values = loader.load(null, true).orElseThrow();

        assertThat(values.provider()).hasValue("SixthLevelProvider");
    }

    @Test
    void load_fileAtSeventhParentLevel_doesNotDiscoverFile() throws Exception {
        Path configDirectory = tempDir.resolve("root");
        Path workingDirectory = Files.createDirectories(configDirectory.resolve("1/2/3/4/5/6/7"));
        writeConfig(configDirectory.resolve(ApiConfigLoader.DEFAULT_FILE_NAME), "SeventhLevelProvider", "true");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(),
            () -> workingDirectory);

        assertThat(loader.load(null, true)).isEmpty();
    }

    @Test
    void load_usesEnvironmentPathBeforeAutoDiscovery() throws Exception {
        Path environmentFile = writeConfig(tempDir.resolve("environment.json"), "EnvironmentProvider", "true");
        Path workingDirectory = Files.createDirectories(tempDir.resolve("work"));
        writeConfig(workingDirectory.resolve(ApiConfigLoader.DEFAULT_FILE_NAME), "DiscoveredProvider", "true");
        MockEnvironment environment = new MockEnvironment().withProperty(ApiConfigLoader.API_CONFIG_ENV,
            environmentFile.toString());
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), environment, () -> workingDirectory);

        ApiConfigLoader.ApiConfigValues values = loader.load(null, true).orElseThrow();

        assertThat(values.provider()).hasValue("EnvironmentProvider");
    }

    @Test
    void load_prefersExplicitPathOverEnvironmentAndAutoDiscovery() throws Exception {
        Path explicitFile = writeConfig(tempDir.resolve("explicit.json"), "ExplicitProvider", "true");
        Path environmentFile = writeConfig(tempDir.resolve("environment.json"), "EnvironmentProvider", "true");
        Path workingDirectory = Files.createDirectories(tempDir.resolve("work"));
        writeConfig(workingDirectory.resolve(ApiConfigLoader.DEFAULT_FILE_NAME), "DiscoveredProvider", "true");
        MockEnvironment environment = new MockEnvironment().withProperty(ApiConfigLoader.API_CONFIG_ENV,
            environmentFile.toString());
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), environment, () -> workingDirectory);

        ApiConfigLoader.ApiConfigValues values = loader.load(explicitFile.toString(), true).orElseThrow();

        assertThat(values.provider()).hasValue("ExplicitProvider");
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

    @Test
    void load_rejectsMalformedJson() throws Exception {
        Path file = tempDir.resolve("malformed.json");
        Files.writeString(file, "{not-json");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);

        assertThatThrownBy(() -> loader.load(file.toString(), false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to read LLM API configuration file");
    }

    @Test
    void load_rejectsJsonRootThatIsNotAnObject() throws Exception {
        Path file = tempDir.resolve("array.json");
        Files.writeString(file, "[\"value\"]");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);

        assertThatThrownBy(() -> loader.load(file.toString(), false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Failed to read LLM API configuration file");
    }

    @Test
    void load_rejectsNullJsonRoot() throws Exception {
        Path file = tempDir.resolve("null.json");
        Files.writeString(file, "null");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);

        assertThatThrownBy(() -> loader.load(file.toString(), false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("must contain a JSON object");
    }

    @Test
    void load_rejectsOversizedFile() throws Exception {
        Path file = tempDir.resolve("oversized.json");
        Files.write(file, new byte[1024 * 1024 + 1]);
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);

        assertThatThrownBy(() -> loader.load(file.toString(), false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("exceeds 1 MiB");
    }

    @Test
    void load_rejectsNonStringTextValue() throws Exception {
        Path file = tempDir.resolve("wrong-type.json");
        Files.writeString(file, "{\"MODEL_NAME\": 1}");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);

        assertThatThrownBy(() -> loader.load(file.toString(), false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("MODEL_NAME")
            .hasMessageContaining("must be a string");
    }

    @Test
    void load_rejectsNonBooleanSslVerifyValue() throws Exception {
        Path file = tempDir.resolve("wrong-ssl-type.json");
        Files.writeString(file, "{\"LLM_SSL_VERIFY\": 1}");
        ApiConfigLoader loader = new ApiConfigLoader(new ObjectMapper(), new MockEnvironment(), () -> tempDir);

        assertThatThrownBy(() -> loader.load(file.toString(), false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("LLM_SSL_VERIFY")
            .hasMessageContaining("must be true or false");
    }

    private static Path writeConfig(Path file, String sslVerify) throws Exception {
        return writeConfig(file, "OpenAI", sslVerify);
    }

    private static Path writeConfig(Path file, String provider, String sslVerify) throws Exception {
        Files.writeString(file, """
            {
              "API_BASE": "https://llm.internal/v1",
              "API_KEY": "ENC:key",
              "MODEL_PROVIDER": "%s",
              "MODEL_NAME": "model-x",
              "LLM_SSL_VERIFY": "%s"
            }
            """.formatted(provider, sslVerify));
        return file;
    }
}
