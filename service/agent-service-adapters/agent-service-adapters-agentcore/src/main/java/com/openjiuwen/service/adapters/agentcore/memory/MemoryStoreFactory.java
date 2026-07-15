/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory;

import com.openjiuwen.service.adapters.agentcore.memory.mem0.GovernedMem0Api;
import com.openjiuwen.service.adapters.agentcore.memory.mem0.Mem0MemoryStore;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

/**
 * Creates provider-specific {@link MemoryStore} implementations from middleware configuration.
 *
 * <p>Each memory backend lives in its own subpackage (for example {@code memory.mem0}). Add a new
 * provider by implementing {@link MemoryStore} in a dedicated subpackage and extending this factory.
 *
 * @since 0.1.0
 */
public final class MemoryStoreFactory {
    private static final String PROVIDER_MEM0 = "mem0";

    private MemoryStoreFactory() {
    }

    /**
     * Creates a memory store for the configured provider.
     *
     * @param apiKey decrypted provider API key
     * @param memory memory middleware configuration
     * @return the configured memory store
     */
    public static MemoryStore create(String apiKey, MiddlewareProperties.Memory memory) {
        if (memory == null) {
            throw new IllegalArgumentException("memory configuration must not be null");
        }
        String provider = memory.getProvider();
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException("openjiuwen.service.middleware.memory.provider must not be blank");
        }
        if (PROVIDER_MEM0.equalsIgnoreCase(provider)) {
            return new Mem0MemoryStore(apiKey, memory, new GovernedMem0Api(memory.getEndpoint(), memory));
        }
        throw new IllegalStateException("Unsupported memory provider: " + provider);
    }
}
