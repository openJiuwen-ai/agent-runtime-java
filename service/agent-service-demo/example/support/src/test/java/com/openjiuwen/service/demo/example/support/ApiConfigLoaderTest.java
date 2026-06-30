/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiConfigLoaderTest {

    @TempDir
    private Path tempDir;

    @Test
    void loadsApiConfigFromExplicitJsonFile() throws Exception {
        Path configFile = tempDir.resolve("apiconfig.json");
        Files.writeString(configFile, """
                {
                  "API_BASE": "https://api.example.com/v1",
                  "API_KEY": "test-key",
                  "MODEL_PROVIDER": "OpenAI",
                  "MODEL_NAME": "test-model",
                  "LLM_SSL_VERIFY": "false"
                }
                """);

        Map<String, String> config = ApiConfigLoader.load(configFile.toString(), false).orElseThrow();

        assertThat(config).containsEntry("API_BASE", "https://api.example.com/v1");
        assertThat(config).containsEntry("API_KEY", "test-key");
        assertThat(config).containsEntry("MODEL_PROVIDER", "OpenAI");
        assertThat(config).containsEntry("MODEL_NAME", "test-model");
        assertThat(config).containsEntry("LLM_SSL_VERIFY", "false");
    }
}
