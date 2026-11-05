/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.middleware;

import com.openjiuwen.core.foundation.store.BaseKVStore;

/**
 * Supplies an application-owned asynchronous KV store for an extension checkpoint backend.
 * This SPI is separate from Core's synchronous {@code com.openjiuwen.spi.store.KVStoreProvider}.
 *
 * @since 0.1.4
 */
public interface CheckpointKVStoreProvider {
    /**
     * Returns the extension token used by checkpointer.type. Built-in types are reserved.
     *
     * @return extension backend type
     */
    String type();

    /**
     * Returns the initialized store for a named connection, reused across registrar applications.
     *
     * @param connectionRef named connection reference
     * @return initialized asynchronous KV store
     */
    BaseKVStore getStore(String connectionRef);
}
