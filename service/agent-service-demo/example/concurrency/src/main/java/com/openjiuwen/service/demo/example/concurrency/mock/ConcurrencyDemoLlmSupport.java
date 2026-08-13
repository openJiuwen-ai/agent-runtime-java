/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.mock;

import com.openjiuwen.service.app.config.llm.LlmConfigResolver;
import com.openjiuwen.service.app.config.llm.ResolvedLlmConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Resolves LLM mode (real vs mock) for the concurrency demo application.
 *
 * @since 0.1.0
 */
@Component
public class ConcurrencyDemoLlmSupport {
    private static final Logger LOG = LoggerFactory.getLogger(ConcurrencyDemoLlmSupport.class);
    private static final String MODE_PROPERTY = "demo.concurrency.llm.mode";
    private static final String DELAY_PROPERTY = "demo.concurrency.llm.mock-delay-ms";
    private static final String SPRING_MODE_PROPERTY = "openjiuwen.service.demo.concurrency.llm.mode";
    private static final String SPRING_DELAY_PROPERTY = "openjiuwen.service.demo.concurrency.llm.mock-delay-ms";

    private final Environment environment;

    /**
     * Creates support bean bound to the Spring environment.
     *
     * @param environment Spring environment for property lookup
     */
    public ConcurrencyDemoLlmSupport(Environment environment) {
        this.environment = environment;
    }

    /**
     * Returns whether the demo should use the registered mock LLM provider.
     *
     * @return {@code true} when mode resolves to {@code mock}
     */
    public boolean isMockMode() {
        return "mock".equalsIgnoreCase(resolveMode());
    }

    /**
     * Returns configured per-call mock LLM latency.
     *
     * @return delay in milliseconds
     */
    public long mockDelayMs() {
        return parseLong(firstNonBlank(
            System.getProperty(DELAY_PROPERTY),
            environment.getProperty(SPRING_DELAY_PROPERTY),
            System.getenv("DEMO_CONCURRENCY_LLM_MOCK_DELAY_MS"),
            String.valueOf(ConcurrencyMockLlmConstants.DEFAULT_DELAY_MS)),
            ConcurrencyMockLlmConstants.DEFAULT_DELAY_MS);
    }

    /**
     * Resolves LLM config for agent construction. Mock mode registers {@link ConcurrencyMockModelClient}
     * and does not require real API credentials.
     *
     * @param llmConfigResolver Spring LLM resolver
     * @return resolved configuration
     */
    public ResolvedLlmConfig resolveForAgent(LlmConfigResolver llmConfigResolver) {
        if (!isMockMode()) {
            LOG.info("Concurrency demo LLM mode: REAL");
            return llmConfigResolver.resolveRequired();
        }
        long delayMs = mockDelayMs();
        ConcurrencyMockLlmBootstrap.ensureRegistered(delayMs);
        ResolvedLlmConfig base = llmConfigResolver.resolve();
        LOG.info("Concurrency demo LLM mode: MOCK (delayMs={}, provider={})", delayMs,
            ConcurrencyMockLlmConstants.PROVIDER);
        return ResolvedLlmConfig.builder()
            .provider(ConcurrencyMockLlmConstants.PROVIDER)
            .apiKey(ConcurrencyMockLlmConstants.API_KEY)
            .apiBase(ConcurrencyMockLlmConstants.API_BASE)
            .modelName(ConcurrencyMockLlmConstants.MODEL_NAME)
            .sslVerify(base.isSslVerify())
            .systemPrompt(base.getSystemPrompt())
            .temperature(base.getTemperature())
            .topP(base.getTopP())
            .timeout(base.getTimeout())
            .contextWindowLimit(base.getContextWindowLimit())
            .maxIterations(base.getMaxIterations())
            .build();
    }

    private String resolveMode() {
        return firstNonBlank(
            System.getProperty(MODE_PROPERTY),
            environment.getProperty(SPRING_MODE_PROPERTY),
            System.getenv("DEMO_CONCURRENCY_LLM_MODE"),
            "real");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static long parseLong(String raw, long defaultValue) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return defaultValue;
        }
    }
}
