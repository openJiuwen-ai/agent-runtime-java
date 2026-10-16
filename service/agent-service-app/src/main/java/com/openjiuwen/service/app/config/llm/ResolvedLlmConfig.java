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
    /**
     * Default connect timeout applied when neither the resolver input nor a
     * direct builder caller provides one (single source shared with
     * {@link LlmConfigResolver}).
     *
     * @since 0.1.4
     */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Default stream idle timeout applied when neither the resolver input nor
     * a direct builder caller provides one (single source shared with
     * {@link LlmConfigResolver}).
     *
     * @since 0.1.4
     */
    public static final Duration DEFAULT_STREAM_IDLE_TIMEOUT = Duration.ofSeconds(600);

    private final String provider;

    private final String apiKey;

    private final String apiBase;

    private final String modelName;

    private final boolean sslVerify;

    private final String systemPrompt;

    private final double temperature;

    private final double topP;

    private final Duration timeout;

    @Builder.Default
    private final Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

    @Builder.Default
    private final Duration streamIdleSeconds = DEFAULT_STREAM_IDLE_TIMEOUT;

    private final boolean isTimeoutExplicit;

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
