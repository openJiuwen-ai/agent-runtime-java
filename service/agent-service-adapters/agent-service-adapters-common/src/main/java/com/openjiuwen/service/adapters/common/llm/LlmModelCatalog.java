/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */
package com.openjiuwen.service.adapters.common.llm;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable deployment model definitions, independent of Spring and the execution framework. */
public final class LlmModelCatalog {
    private final String defaultId;
    private final Map<String, ModelDefinition> models;
    private final double temperature;
    private final double topP;
    private final Duration timeout;

    /** Creates a validated catalog; keys are public, case-sensitive aliases. */
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

    public String defaultId() { return defaultId; }
    public Map<String, ModelDefinition> models() { return models; }
    public double temperature() { return temperature; }
    public double topP() { return topP; }
    public Duration timeout() { return timeout; }

    /** Connection fields are server-side only; never persist these in request snapshots. */
    public record ModelDefinition(String provider, String apiKey, String apiBase, String modelName,
                                  boolean sslVerify) {
        public ModelDefinition {
            requireText(provider, "provider");
            requireText(apiKey, "apiKey");
            requireText(apiBase, "apiBase");
            requireText(modelName, "modelName");
        }

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
