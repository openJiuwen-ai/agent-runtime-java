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
    public static final String PROVIDER = "ConcurrencyMockLlmProvider";

    public static final String API_BASE = "mirror://concurrency-mock-llm";

    public static final String MODEL_NAME = "concurrency-mock-model";

    public static final String API_KEY = "mock-key";

    public static final long DEFAULT_DELAY_MS = 3000L;

    private ConcurrencyMockLlmConstants() {
    }
}
