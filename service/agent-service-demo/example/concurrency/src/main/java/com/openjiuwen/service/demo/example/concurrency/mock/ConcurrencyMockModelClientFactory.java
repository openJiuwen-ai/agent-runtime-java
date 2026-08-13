/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.concurrency.mock;

import com.openjiuwen.core.foundation.llm.Model;
import com.openjiuwen.core.foundation.llm.model_clients.BaseModelClient;
import com.openjiuwen.core.foundation.llm.schema.ModelClientConfig;
import com.openjiuwen.core.foundation.llm.schema.ModelRequestConfig;

/**
 * Factory for {@link ConcurrencyMockModelClient}.
 *
 * @since 0.1.0
 */
public final class ConcurrencyMockModelClientFactory implements Model.ModelClientFactory {
    private final long delayMs;

    /**
     * Creates a factory that applies the given mock latency to each client.
     *
     * @param delayMs fixed delay in milliseconds
     */
    public ConcurrencyMockModelClientFactory(long delayMs) {
        this.delayMs = delayMs;
    }

    /**
     * Returns the registered mock provider name.
     *
     * @return provider identifier
     */
    @Override
    public String providerName() {
        return ConcurrencyMockLlmConstants.PROVIDER;
    }

    /**
     * Creates a mock model client with configured latency.
     *
     * @param modelConfig model request configuration
     * @param clientConfig transport configuration
     * @return mock model client instance
     */
    @Override
    public BaseModelClient create(ModelRequestConfig modelConfig, ModelClientConfig clientConfig) {
        return new ConcurrencyMockModelClient(modelConfig, clientConfig, delayMs);
    }
}
