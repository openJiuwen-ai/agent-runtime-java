/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openjiuwen.service.adapters.common.credential.CredentialDecryptor;
import com.openjiuwen.service.adapters.common.credential.CredentialSceneType;

import org.springframework.core.env.Environment;

import java.time.Duration;

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

    private static final int DEFAULT_CONTEXT_WINDOW_LIMIT = 10;

    private static final int DEFAULT_MAX_ITERATIONS = 5;

    private final LlmProperties properties;

    private final ApiConfigLoader apiConfigLoader;

    private final CredentialDecryptor credentialDecryptor;

    private ResolvedLlmConfig resolved;

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

    private ResolvedLlmConfig doResolve() {
        boolean autoDiscover = Boolean.TRUE.equals(properties.getAutoDiscover());
        ApiConfigLoader.ApiConfigValues fileValues = apiConfigLoader
            .load(properties.getConfigFile(), autoDiscover)
            .orElseGet(() -> new ApiConfigLoader.ApiConfigValues(null, null, null, null, null));

        String encryptedApiKey = firstSecret(properties.getApiKey(), fileValues.apiKey());
        String apiKey = decryptApiKey(encryptedApiKey);
        double temperature = valueOrDefault(properties.getTemperature(), DEFAULT_TEMPERATURE);
        double topP = valueOrDefault(properties.getTopP(), DEFAULT_TOP_P);
        Duration timeout = properties.getTimeout() != null ? properties.getTimeout() : DEFAULT_TIMEOUT;
        int contextWindowLimit = valueOrDefault(properties.getContextWindowLimit(), DEFAULT_CONTEXT_WINDOW_LIMIT);
        int maxIterations = valueOrDefault(properties.getMaxIterations(), DEFAULT_MAX_ITERATIONS);

        validateRuntimeValues(temperature, topP, timeout, contextWindowLimit, maxIterations);

        return ResolvedLlmConfig.builder()
            .provider(firstText(properties.getProvider(), fileValues.provider(), DEFAULT_PROVIDER))
            .apiKey(apiKey)
            .apiBase(firstText(properties.getApiBase(), fileValues.apiBase(), ""))
            .modelName(firstText(properties.getModelName(), fileValues.modelName(), ""))
            .sslVerify(firstBoolean(properties.getSslVerify(), fileValues.sslVerify(), true))
            .systemPrompt(properties.getSystemPrompt() != null ? properties.getSystemPrompt() : "")
            .temperature(temperature)
            .topP(topP)
            .timeout(timeout)
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

    private static void requireText(String value, String propertyName) {
        if (!hasText(value)) {
            throw new IllegalStateException(propertyName + " is required for an outbound LLM agent");
        }
    }

    private static String firstText(String primary, String secondary, String fallback) {
        if (hasText(primary)) {
            return primary.trim();
        }
        if (hasText(secondary)) {
            return secondary.trim();
        }
        return fallback;
    }

    private static String firstSecret(String primary, String secondary) {
        if (hasText(primary)) {
            return primary;
        }
        if (hasText(secondary)) {
            return secondary;
        }
        return "";
    }

    private static boolean firstBoolean(Boolean primary, Boolean secondary, boolean fallback) {
        if (primary != null) {
            return primary;
        }
        if (secondary != null) {
            return secondary;
        }
        return fallback;
    }

    private static double valueOrDefault(Double value, double fallback) {
        return value != null ? value : fallback;
    }

    private static int valueOrDefault(Integer value, int fallback) {
        return value != null ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
