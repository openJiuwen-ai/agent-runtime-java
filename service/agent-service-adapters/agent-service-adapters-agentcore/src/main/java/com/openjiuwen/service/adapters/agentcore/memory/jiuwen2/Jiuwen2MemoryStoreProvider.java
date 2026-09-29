/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.jiuwen2;

import com.openjiuwen.service.adapters.agentcore.memory.MemoryStoreProvider;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

/**
 * {@link MemoryStoreProvider} for agent-memory 2.0.
 *
 * @since 0.1.0
 */
public class Jiuwen2MemoryStoreProvider implements MemoryStoreProvider {
    @Override
    public String providerName() {
        return "jiuwen2";
    }

    @Override
    public MemoryStore create(String apiKey, MiddlewareProperties.Memory memory) {
        return new Jiuwen2MemoryStore(apiKey, memory,
            new Jiuwen2MemoryApi(memory.getEndpoint(), memory, apiKey));
    }
}
