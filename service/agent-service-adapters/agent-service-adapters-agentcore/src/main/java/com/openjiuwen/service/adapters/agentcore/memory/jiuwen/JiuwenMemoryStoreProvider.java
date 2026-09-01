/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.adapters.agentcore.memory.jiuwen;

import com.openjiuwen.service.adapters.agentcore.memory.MemoryStoreProvider;
import com.openjiuwen.service.adapters.common.memory.MemoryStore;
import com.openjiuwen.service.adapters.common.middleware.MiddlewareProperties;

/**
 * {@link MemoryStoreProvider} for the Jiuwen Memory Engine.
 *
 * @since 0.1.0
 */
public class JiuwenMemoryStoreProvider implements MemoryStoreProvider {
    @Override
    public String providerName() {
        return "jiuwen";
    }

    @Override
    public MemoryStore create(String apiKey, MiddlewareProperties.Memory memory) {
        return new JiuwenMemoryStore(apiKey, memory,
            new JiuwenMemoryApi(memory.getEndpoint(), memory, apiKey));
    }
}
