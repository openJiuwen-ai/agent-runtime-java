/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;
import com.openjiuwen.service.adapters.common.llm.LlmModelCatalog;

import org.springframework.core.env.Environment;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves raw Spring and optional file values into an immutable LLM runtime
 * configuration.
 *
 * @since 0.1.0
 */
public final class LlmConfigResolver {
    private static final String DEFAULT_PROVIDER = "OpenAI";

    private static final double DEFAULT_TEMPERATURE = 0.6D;

    private static final double DEFAULT_TOP_P = 0.8D;

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);

    private static final Duration MIN_CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final Duration MAX_CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private static final Duration MIN_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(300);

    private static final Duration MAX_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(900);

    private static final int DEFAULT_CONTEXT_WINDOW_LIMIT = 10;

    private static final int DEFAULT_MAX_ITERATIONS = 5;

    private final LlmProperties properties;

    private final ApiConfigLoader apiConfigLoader;

    private final CredentialDecryptor credentialDecryptor;

    private ResolvedLlmConfig resolved;

    private LlmModelCatalog catalog;

    /**
     * Creates an LLM configuration resolver.
     *
     * @param properties raw Spring configuration
     * @param environment Spring environment used to locate optional API config
     * @param credentialDecryptor credential decryption SPI
     */
    public LlmConfigResolver(LlmProperties properties, Environment environment,
        CredentialDecryptor credentialDecryptor) {
        this(properties, new ApiConfigLoader(new ObjectMapper(), environment), credentialDecryptor);
    }

    LlmConfigResolver(LlmProperties properties, ApiConfigLoader apiConfigLoader,
        CredentialDecryptor credentialDecryptor) {
        this.properties = properties;
        this.apiConfigLoader = apiConfigLoader;
        this.credentialDecryptor = credentialDecryptor;
    }

    /**
     * Resolves configured values without requiring LLM endpoint credentials.
     * The resolved result is cached because credential decryption may call an
     * external KMS.
     *
     * @return resolved immutable LLM configuration
     */
    public synchronized ResolvedLlmConfig resolve() {
        if (resolved == null) {
            resolved = doResolve();
        }
        return resolved;
    }

    /**
     * Resolves configured values and requires a complete outbound LLM endpoint.
     *
     * @return resolved immutable LLM configuration
     * @throws IllegalStateException if required LLM properties are missing
     */
    public ResolvedLlmConfig resolveRequired() {
        ResolvedLlmConfig config = resolve();
        requireText(config.getApiKey(), "openjiuwen.service.llm.api-key");
        requireText(config.getApiBase(), "openjiuwen.service.llm.api-base");
        requireText(config.getModelName(), "openjiuwen.service.llm.model-name");
        return config;
    }

    /**
     * Resolves the fixed deployment catalog from the same snapshot as the default model.
     * Explicit Java callers may opt in independently of Spring auto-configuration.
     *
     * @return validated, decrypted catalog
     */
    public synchronized LlmModelCatalog resolveCatalog() {
        if (catalog != null) {
            return catalog;
        }
        ResolvedLlmConfig defaults = resolveRequired();
        String defaultId = properties.getModelId() == null ? "default"
            : requireModelId(properties.getModelId(), "openjiuwen.service.llm.model-id");
        Map<String, LlmModelCatalog.ModelDefinition> models = new LinkedHashMap<>();
        models.put(defaultId, new LlmModelCatalog.ModelDefinition(defaults.getProvider(), defaults.getApiKey(),
            defaults.getApiBase(), defaults.getModelName(), defaults.isSslVerify()));
        if (properties.getModels() != null) {
            for (LlmProperties.ModelProperties item : properties.getModels()) {
                if (item == null) {
                    throw new IllegalStateException("openjiuwen.service.llm.models entries must be objects");
                }
                addModel(models, item);
            }
        }
        catalog = new LlmModelCatalog(defaultId, models, defaults.getTemperature(), defaults.getTopP(),
            defaults.getTimeout());
        return catalog;
    }

    private void addModel(Map<String, LlmModelCatalog.ModelDefinition> models,
        LlmProperties.ModelProperties item) {
        String source = "openjiuwen.service.llm.models";
        String id = requireModelId(item.getModelId(), source + ".model-id");
        if (models.containsKey(id)) {
            throw new IllegalStateException("Duplicate model-id in LLM catalog");
        }
        String key = decryptApiKey(firstSecret(item.getApiKey(), Optional.empty()));
        String base = firstText(item.getApiBase(), Optional.empty(), "");
        String name = firstText(item.getModelName(), Optional.empty(), "");
        requireText(key, source + ".api-key");
        requireText(base, source + ".api-base");
        requireText(name, source + ".model-name");
        models.put(id, new LlmModelCatalog.ModelDefinition(
            firstText(item.getProvider(), Optional.empty(), DEFAULT_PROVIDER), key, base, name,
            item.getSslVerify() == null || item.getSslVerify()));
    }

    private static String requireModelId(String id, String propertyName) {
        requireText(id, propertyName);
        return id.trim();
    }

    private ResolvedLlmConfig doResolve() {
        ApiConfigLoader.ApiConfigValues fileValues = apiConfigLoader.load(properties.getConfigFile(),
                Boolean.TRUE.equals(properties.getAutoDiscover()))
            .orElseGet(ApiConfigLoader.ApiConfigValues::empty);

        double temperature = valueOrDefault(properties.getTemperature(), DEFAULT_TEMPERATURE);
        double topP = valueOrDefault(properties.getTopP(), DEFAULT_TOP_P);
        Duration timeout = properties.getTimeout() != null ? properties.getTimeout() : DEFAULT_TIMEOUT;
        Duration connectTimeout = properties.getConnectTimeout() != null
            ? properties.getConnectTimeout() : ResolvedLlmConfig.DEFAULT_CONNECT_TIMEOUT;
        Duration streamIdleTimeout = properties.getStreamIdleSeconds() != null
            ? properties.getStreamIdleSeconds() : ResolvedLlmConfig.DEFAULT_STREAM_IDLE_TIMEOUT;
        int contextWindowLimit = valueOrDefault(properties.getContextWindowLimit(), DEFAULT_CONTEXT_WINDOW_LIMIT);
        int maxIterations = valueOrDefault(properties.getMaxIterations(), DEFAULT_MAX_ITERATIONS);

        validateRuntimeValues(temperature, topP, timeout, contextWindowLimit, maxIterations);
        validateTimeoutTiering(connectTimeout, streamIdleTimeout);

        return ResolvedLlmConfig.builder()
            .provider(firstText(properties.getProvider(), fileValues.provider(), DEFAULT_PROVIDER))
            .apiKey(decryptApiKey(firstSecret(properties.getApiKey(), fileValues.apiKey())))
            .apiBase(firstText(properties.getApiBase(), fileValues.apiBase(), ""))
            .modelName(firstText(properties.getModelName(), fileValues.modelName(), ""))
            .sslVerify(resolveSslVerify(Optional.ofNullable(properties.getSslVerify()),
                fileValues.shouldVerifySsl(), true))
            .systemPrompt(properties.getSystemPrompt() != null ? properties.getSystemPrompt() : "")
            .temperature(temperature)
            .topP(topP)
            .timeout(timeout)
            .connectTimeout(connectTimeout)
            .streamIdleSeconds(streamIdleTimeout)
            .isTimeoutExplicit(properties.getTimeout() != null)
            .contextWindowLimit(contextWindowLimit)
            .maxIterations(maxIterations)
            .build();
    }

    private String decryptApiKey(String encryptedApiKey) {
        if (!hasText(encryptedApiKey)) {
            return "";
        }
        String decrypted = credentialDecryptor.decrypt(encryptedApiKey, CredentialSceneType.LLM_API_KEY);
        return decrypted != null ? decrypted : "";
    }

    private static void validateRuntimeValues(double temperature, double topP, Duration timeout,
        int contextWindowLimit, int maxIterations) {
        if (!Double.isFinite(temperature) || temperature < 0.0D) {
            throw new IllegalStateException("openjiuwen.service.llm.temperature must be a finite non-negative value");
        }
        if (!Double.isFinite(topP) || topP < 0.0D || topP > 1.0D) {
            throw new IllegalStateException("openjiuwen.service.llm.top-p must be between 0 and 1");
        }
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalStateException("openjiuwen.service.llm.timeout must be greater than zero");
        }
        if (contextWindowLimit <= 0) {
            throw new IllegalStateException("openjiuwen.service.llm.context-window-limit must be greater than zero");
        }
        if (maxIterations <= 0) {
            throw new IllegalStateException("openjiuwen.service.llm.max-iterations must be greater than zero");
        }
    }

    private static void validateTimeoutTiering(Duration connectTimeout, Duration streamIdleTimeout) {
        if (connectTimeout.compareTo(MIN_CONNECT_TIMEOUT) < 0 || connectTimeout.compareTo(MAX_CONNECT_TIMEOUT) > 0) {
            throw new IllegalStateException(
                "openjiuwen.service.llm.connect-timeout must be between 5 and 15 seconds");
        }
        if (streamIdleTimeout.compareTo(MIN_STREAM_IDLE_TIMEOUT) < 0
            || streamIdleTimeout.compareTo(MAX_STREAM_IDLE_TIMEOUT) > 0) {
            throw new IllegalStateException(
                "openjiuwen.service.llm.stream-idle-seconds must be between 300 and 900 seconds");
        }
    }

    private static void requireText(String value, String propertyName) {
        if (!hasText(value)) {
            throw new IllegalStateException(propertyName + " is required for an outbound LLM agent");
        }
    }

    private static String firstText(String primary, Optional<String> secondary, String fallback) {
        if (hasText(primary)) {
            return primary.trim();
        }
        return secondary.filter(LlmConfigResolver::hasText).map(String::trim).orElse(fallback);
    }

    private static String firstSecret(String primary, Optional<String> secondary) {
        if (hasText(primary)) {
            return primary;
        }
        return secondary.filter(LlmConfigResolver::hasText).orElse("");
    }

    private static boolean resolveSslVerify(Optional<Boolean> configuredOption, Optional<Boolean> fileOption,
        boolean shouldVerifyByDefault) {
        return configuredOption.or(() -> fileOption).orElse(shouldVerifyByDefault);
    }

    private static double valueOrDefault(Double value, double fallback) {
        if (value != null) {
            return value.doubleValue();
        }
        return fallback;
    }

    private static int valueOrDefault(Integer value, int fallback) {
        if (value != null) {
            return value.intValue();
        }
        return fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
