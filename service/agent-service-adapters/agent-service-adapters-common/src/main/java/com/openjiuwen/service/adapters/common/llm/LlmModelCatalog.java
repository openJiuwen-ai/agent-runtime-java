/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.service.adapters.common.llm;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable deployment model definitions, independent of Spring and the execution framework.
 *
 * @since 2026-10-09
 */
public final class LlmModelCatalog {
    private final String defaultId;
    private final Map<String, ModelDefinition> models;
    private final double temperature;
    private final double topP;
    private final Duration timeout;

    /**
     * Creates a validated catalog; keys are public, case-sensitive aliases.
     *
     * @param defaultId default public model alias
     * @param models model definitions indexed by public alias
     * @param temperature shared sampling temperature
     * @param topP shared nucleus sampling probability
     * @param timeout shared request timeout
     * @throws IllegalArgumentException if catalog values are invalid
     * @throws NullPointerException if the model map or a model definition is null
     */
    public LlmModelCatalog(String defaultId, Map<String, ModelDefinition> models,
            double temperature, double topP, Duration timeout) {
        requireText(defaultId, "defaultId");
        Objects.requireNonNull(models, "models");
        Map<String, ModelDefinition> copy = new LinkedHashMap<>();
        models.forEach((id, model) -> {
            requireText(id, "modelId");
            if (!id.equals(id.trim())) {
                throw new IllegalArgumentException("modelId must be trimmed");
            }
            copy.put(id, Objects.requireNonNull(model, "model"));
        });
        if (!copy.containsKey(defaultId)) {
            throw new IllegalArgumentException("Catalog must contain its default model");
        }
        if (!Double.isFinite(temperature) || temperature < 0 || !Double.isFinite(topP) || topP < 0 || topP > 1) {
            throw new IllegalArgumentException("Invalid model sampling parameters");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Model timeout must be positive");
        }
        this.defaultId = defaultId;
        this.models = Collections.unmodifiableMap(copy);
        this.temperature = temperature;
        this.topP = topP;
        this.timeout = timeout;
    }

    /**
     * Returns the default public model alias.
     *
     * @return default public model alias
     */
    public String defaultId() {
        return defaultId;
    }

    /**
     * Returns the immutable model definitions.
     *
     * @return immutable model definitions
     */
    public Map<String, ModelDefinition> models() {
        return models;
    }

    /**
     * Returns the shared sampling temperature.
     *
     * @return shared sampling temperature
     */
    public double temperature() {
        return temperature;
    }

    /**
     * Returns the shared nucleus sampling probability.
     *
     * @return shared nucleus sampling probability
     */
    public double topP() {
        return topP;
    }

    /**
     * Returns the shared request timeout.
     *
     * @return shared request timeout
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * Connection fields are server-side only; never persist these in request snapshots.
     *
     * @param provider model client provider
     * @param apiKey decrypted API credential
     * @param apiBase provider endpoint
     * @param modelName provider model name
     * @param shouldVerifySsl whether to verify SSL certificates
     */
    public record ModelDefinition(String provider, String apiKey, String apiBase, String modelName,
            boolean shouldVerifySsl) {
        /**
         * Validates the required connection fields.
         *
         * @param provider model client provider
         * @param apiKey decrypted API credential
         * @param apiBase provider endpoint
         * @param modelName provider model name
         * @param shouldVerifySsl whether to verify SSL certificates
         * @throws IllegalArgumentException if a required field is blank
         */
        public ModelDefinition {
            requireText(provider, "provider");
            requireText(apiKey, "apiKey");
            requireText(apiBase, "apiBase");
            requireText(modelName, "modelName");
        }

        /**
         * Returns the SSL verification setting using the existing catalog accessor.
         *
         * @return whether to verify SSL certificates
         */
        public boolean sslVerify() {
            return shouldVerifySsl;
        }

        /**
         * Returns a credential-safe description.
         *
         * @return redacted model description
         */
        @Override
        public String toString() {
            return "ModelDefinition[connection and credentials redacted]";
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
