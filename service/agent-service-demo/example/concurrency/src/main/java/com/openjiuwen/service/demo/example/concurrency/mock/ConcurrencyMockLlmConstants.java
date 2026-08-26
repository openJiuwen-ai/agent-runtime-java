/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.mock;

/**
 * Constants for concurrency demo mock LLM provider registration.
 *
 * @since 0.1.0
 */
public final class ConcurrencyMockLlmConstants {
    /**
     * Registered mock LLM provider name.
     */
    public static final String PROVIDER = "ConcurrencyMockLlmProvider";

    /**
     * Mirror API base used by the mock client factory.
     */
    public static final String API_BASE = "mirror://concurrency-mock-llm";

    /**
     * Default mock model identifier.
     */
    public static final String MODEL_NAME = "concurrency-mock-model";

    /**
     * Mock-only placeholder, not a real API key. For test/demo use only — never use in production.
     */
    public static final String API_KEY = "mock-key";

    /**
     * Default per-call mock LLM latency in milliseconds.
     */
    public static final long DEFAULT_DELAY_MS = 3000L;

    private ConcurrencyMockLlmConstants() {
    }
}
