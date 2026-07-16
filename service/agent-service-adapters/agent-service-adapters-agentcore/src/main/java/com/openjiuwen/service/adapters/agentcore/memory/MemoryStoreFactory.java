/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory;

import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Creates provider-specific {@link MemoryStore} implementations from middleware configuration.
 *
 * <p>Routes to the matching {@link MemoryStoreProvider} by
 * {@code memory.getProvider()}. To add a new provider, implement
 * {@link MemoryStoreProvider} and register it as a Spring Bean — no changes
 * to this class are needed.
 *
 * @since 0.1.0
 */
public final class MemoryStoreFactory {
    private final Map<String, MemoryStoreProvider> providers;

    /**
     * Creates a factory backed by the given providers.
     *
     * @param providerList all available memory store providers
     */
    public MemoryStoreFactory(List<MemoryStoreProvider> providerList) {
        Map<String, MemoryStoreProvider> map = new LinkedHashMap<>();
        if (providerList != null) {
            for (MemoryStoreProvider p : providerList) {
                map.put(p.providerName().toLowerCase(), p);
            }
        }
        this.providers = Collections.unmodifiableMap(map);
    }

    /**
     * Creates a memory store for the configured provider.
     *
     * @param apiKey decrypted provider API key
     * @param memory memory middleware configuration
     * @return the configured memory store
     * @throws IllegalStateException if the provider is not registered
     */
    public MemoryStore create(String apiKey, MiddlewareProperties.Memory memory) {
        if (memory == null) {
            throw new IllegalArgumentException("memory configuration must not be null");
        }
        String provider = memory.getProvider();
        if (provider == null || provider.isBlank()) {
            throw new IllegalStateException("openjiuwen.service.middleware.memory.provider must not be blank");
        }
        MemoryStoreProvider storeProvider = providers.get(provider.toLowerCase());
        if (storeProvider == null) {
            throw new IllegalStateException(
                "Unsupported memory provider: " + provider + ". Available: " + providers.keySet());
        }
        return storeProvider.create(apiKey, memory);
    }
}
