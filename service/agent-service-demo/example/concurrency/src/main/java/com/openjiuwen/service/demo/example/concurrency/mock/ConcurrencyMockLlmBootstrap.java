/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.mock;

import com.openjiuwen.core.foundation.llm.Model;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers the concurrency demo mock LLM factory once per JVM.
 *
 * @since 0.1.0
 */
public final class ConcurrencyMockLlmBootstrap {
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();

    private ConcurrencyMockLlmBootstrap() {
    }

    /**
     * Ensures {@link ConcurrencyMockModelClientFactory} is registered with the given delay.
     *
     * @param delayMs fixed delay applied on each LLM invoke/stream call
     */
    public static void ensureRegistered(long delayMs) {
        if (REGISTERED.compareAndSet(false, true)) {
            Model.registerFactory(new ConcurrencyMockModelClientFactory(delayMs));
        }
    }
}
