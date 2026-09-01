/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.mem0;

import com.openjiuwen.service.adapters.agentcore.memory.MemoryStoreProvider;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

/**
 * {@link MemoryStoreProvider} for the mem0 memory service.
 *
 * @since 0.1.0
 */
public class Mem0MemoryStoreProvider implements MemoryStoreProvider {
    @Override
    public String providerName() {
        return "mem0";
    }

    @Override
    public MemoryStore create(String apiKey, MiddlewareProperties.Memory memory) {
        return new Mem0MemoryStore(apiKey, memory,
            new GovernedMem0Api(memory.getEndpoint(), memory, memory.getAuthHeaderMode(), apiKey,
                memory.getPathStyle()));
    }
}
