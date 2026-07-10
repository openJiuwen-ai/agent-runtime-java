/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.config.llm;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;

/**
 * Immutable LLM runtime configuration after defaults, file values, and
 * credential decryption have been applied.
 *
 * @since 0.1.0
 */
@Getter
@Builder
public final class ResolvedLlmConfig {
    private final String provider;

    private final String apiKey;

    private final String apiBase;

    private final String modelName;

    private final boolean verifySsl;

    private final String systemPrompt;

    private final double temperature;

    private final double topP;

    private final Duration timeout;

    private final int contextWindowLimit;

    private final int maxIterations;

    /**
     * Prevents the generated builder from exposing API keys through its
     * diagnostic representation.
     *
     * @since 0.1.0
     */
    public static class ResolvedLlmConfigBuilder {
        @Override
        public String toString() {
            return "ResolvedLlmConfig.ResolvedLlmConfigBuilder(apiKey=******)";
        }
    }
}
