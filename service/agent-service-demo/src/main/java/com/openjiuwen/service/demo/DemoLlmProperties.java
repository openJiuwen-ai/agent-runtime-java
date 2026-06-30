/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@Data
@ConfigurationProperties(prefix = "openjiuwen.demo.llm")
public class DemoLlmProperties {

    private String configFile;
    private boolean autoDiscover = true;
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
    private int maxIterations = 5;

    void requireConfigured() {
        requireText(apiKey, "openjiuwen.demo.llm.api-key");
        requireText(apiBase, "openjiuwen.demo.llm.api-base");
        requireText(modelName, "openjiuwen.demo.llm.model-name");
    }

    void applyFromFile(Map<String, String> fileConfig) {
        if (fileConfig == null) {
            return;
        }
        if (!hasText(apiBase)) {
            apiBase = trimToEmpty(fileConfig.get(ApiConfigLoader.KEY_API_BASE));
        }
        if (!hasText(apiKey)) {
            apiKey = trimToEmpty(fileConfig.get(ApiConfigLoader.KEY_API_KEY));
        }
        if (!hasText(modelName)) {
            modelName = trimToEmpty(fileConfig.get(ApiConfigLoader.KEY_MODEL_NAME));
        }
        String fileProvider = trimToNull(fileConfig.get(ApiConfigLoader.KEY_PROVIDER));
        if (fileProvider != null) {
            provider = fileProvider;
        }
        String sslVerifyValue = trimToNull(fileConfig.get(ApiConfigLoader.KEY_SSL_VERIFY));
        if (sslVerifyValue != null) {
            sslVerify = Boolean.parseBoolean(sslVerifyValue);
        }
    }

    public void setProvider(String provider) {
        this.provider = hasText(provider) ? provider : "OpenAI";
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey != null ? apiKey : "";
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase != null ? apiBase : "";
    }

    public void setModelName(String modelName) {
        this.modelName = modelName != null ? modelName : "";
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = hasText(systemPrompt)
                ? systemPrompt
                : "You are a helpful assistant. Answer concisely and accurately.";
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(60);
    }

    public void setContextWindowLimit(int contextWindowLimit) {
        this.contextWindowLimit = contextWindowLimit > 0 ? contextWindowLimit : 10;
    }

    public void setMaxIterations(int maxIterations) {
        this.maxIterations = maxIterations > 0 ? maxIterations : 5;
    }

    private static void requireText(String value, String propertyName) {
        if (!hasText(value)) {
            throw new IllegalStateException(propertyName + " is required when the demo LLM handler is enabled");
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
