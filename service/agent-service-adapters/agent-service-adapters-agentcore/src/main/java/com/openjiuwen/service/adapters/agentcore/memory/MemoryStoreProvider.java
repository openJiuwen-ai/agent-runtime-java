/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory;

import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

/**
 * SPI for pluggable {@link MemoryStore} creation.
 *
 * <p>Each memory provider implements this interface and registers itself as a Spring Bean
 * (typically via {@code @Bean} in auto-configuration). {@link MemoryStoreFactory} collects
 * all available providers and routes by {@link #providerName()}.
 *
 * <p>To add a new memory provider:
 * <ol>
 *   <li>Implement {@link MemoryStore} in a dedicated subpackage.</li>
 *   <li>Implement this interface to wire configuration into the store.</li>
 *   <li>Register the provider as a {@code @Bean} in auto-configuration.</li>
 * </ol>
 *
 * @since 0.1.0
 */
public interface MemoryStoreProvider {
    /**
     * Provider identifier matching the {@code openjiuwen.service.middleware.memory.provider}
     * configuration value.
     *
     * @return provider name, for example {@code "mem0"} or {@code "jiuwen"}
     */
    String providerName();

    /**
     * Creates a configured {@link MemoryStore} instance.
     *
     * @param apiKey decrypted provider API key
     * @param memory memory middleware configuration
     * @return the configured memory store
     */
    MemoryStore create(String apiKey, MiddlewareProperties.Memory memory);
}
