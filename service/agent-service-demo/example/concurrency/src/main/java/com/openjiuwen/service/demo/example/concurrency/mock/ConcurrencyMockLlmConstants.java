/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.mock;

import java.security.SecureRandom;
import java.util.Base64;

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
     * Placeholder API key for mock mode, generated once per JVM start. The mock model client never
     * reads this value; it only satisfies the non-blank API key expectation of the resolved LLM
     * config, so it is never persisted and never used to authenticate against any real service.
     */
    public static final String API_KEY = randomApiKey();

    /**
     * Default per-call mock LLM latency in milliseconds.
     */
    public static final long DEFAULT_DELAY_MS = 3000L;

    private ConcurrencyMockLlmConstants() {
    }

    private static String randomApiKey() {
        byte[] entropy = new byte[16];
        new SecureRandom().nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
}
