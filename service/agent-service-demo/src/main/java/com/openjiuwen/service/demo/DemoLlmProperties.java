/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

@ConfigurationProperties(prefix = "openjiuwen.demo.llm")
public class DemoLlmProperties {

    private Boolean enabled;
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

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    boolean shouldUseLlm() {
        if (enabled != null) {
            return enabled;
        }
        return isConfigured();
    }

    boolean isConfigured() {
        return hasText(apiKey) && hasText(apiBase) && hasText(modelName);
    }

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

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public boolean isAutoDiscover() {
        return autoDiscover;
    }

    public void setAutoDiscover(boolean autoDiscover) {
        this.autoDiscover = autoDiscover;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = hasText(provider) ? provider : "OpenAI";
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey != null ? apiKey : "";
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase != null ? apiBase : "";
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName != null ? modelName : "";
    }

    public boolean isSslVerify() {
        return sslVerify;
    }

    public void setSslVerify(boolean sslVerify) {
        this.sslVerify = sslVerify;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = hasText(systemPrompt)
                ? systemPrompt
                : "You are a helpful assistant. Answer concisely and accurately.";
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
        this.temperature = temperature;
    }

    public Double getTopP() {
        return topP;
    }

    public void setTopP(Double topP) {
        this.topP = topP;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(60);
    }

    public int getContextWindowLimit() {
        return contextWindowLimit;
    }

    public void setContextWindowLimit(int contextWindowLimit) {
        this.contextWindowLimit = contextWindowLimit > 0 ? contextWindowLimit : 10;
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
