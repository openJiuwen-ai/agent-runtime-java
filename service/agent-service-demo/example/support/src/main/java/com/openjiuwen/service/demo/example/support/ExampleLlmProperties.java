/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * LLM configuration for example feature modules. Bound from {@code openjiuwen.example.llm} in
 * {@code example/config/application-base.yml}.
 *
 * @since 0.1.0
 */
@Data
@ConfigurationProperties(prefix = "openjiuwen.example.llm")
public class ExampleLlmProperties {

    private boolean autoDiscover = true;
    private String configFile;
    private String provider = "OpenAI";
    private String apiKey = "";
    private String apiBase = "";
    private String modelName = "";
    private boolean sslVerify = true;
    private String systemPrompt = "You are a helpful assistant. Answer concisely and accurately.";
    private Double temperature = 0.6;
    private Double topP = 0.8;
    private Duration timeout = Duration.ofSeconds(60);
    private int contextWindowLimit = 10;

    public void applyApiConfigIfPresent() {
        ExampleApiConfigLoader.load(configFile, autoDiscover).ifPresent(this::applyFromFile);
    }

    public void requireConfigured() {
        requireText(apiKey, "openjiuwen.example.llm.api-key");
        requireText(apiBase, "openjiuwen.example.llm.api-base");
        requireText(modelName, "openjiuwen.example.llm.model-name");
    }

    void applyFromFile(Map<String, String> fileConfig) {
        if (fileConfig == null) {
            return;
        }
        if (!hasText(apiBase)) {
            apiBase = trimToEmpty(fileConfig.get(ExampleApiConfigLoader.KEY_API_BASE));
        }
        if (!hasText(apiKey)) {
            apiKey = trimToEmpty(fileConfig.get(ExampleApiConfigLoader.KEY_API_KEY));
        }
        if (!hasText(modelName)) {
            modelName = trimToEmpty(fileConfig.get(ExampleApiConfigLoader.KEY_MODEL_NAME));
        }
        String fileProvider = trimToNull(fileConfig.get(ExampleApiConfigLoader.KEY_PROVIDER));
        if (fileProvider != null) {
            provider = fileProvider;
        }
        String sslVerifyValue = trimToNull(fileConfig.get(ExampleApiConfigLoader.KEY_SSL_VERIFY));
        if (sslVerifyValue != null) {
            sslVerify = Boolean.parseBoolean(sslVerifyValue);
        }
    }

    private static void requireText(String value, String propertyName) {
        if (!hasText(value)) {
            throw new IllegalStateException(propertyName + " is required for example feature modules");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToEmpty(String value) {
        String trimmed = trimToNull(value);
        return trimmed != null ? trimmed : "";
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
