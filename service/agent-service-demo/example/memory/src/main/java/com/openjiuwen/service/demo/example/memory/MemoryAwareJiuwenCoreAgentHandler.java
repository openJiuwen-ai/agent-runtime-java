/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.demo.example.memory;

import com.openjiuwen.service.adapters.agentcore.agentfw.JiuwenCoreAgentHandler;
import com.openjiuwen.service.adapters.agentcore.external.ExternalSvcAdapterRegistrar;
import com.openjiuwen.service.spec.dto.ServeRequest;

/**
 * Memory-demo handler that can opt in to request-scoped Core sessions.
 *
 * @since 0.1.0
 */
final class MemoryAwareJiuwenCoreAgentHandler extends JiuwenCoreAgentHandler {
    private final boolean requestScopedSession;

    MemoryAwareJiuwenCoreAgentHandler(Object agent, ExternalSvcAdapterRegistrar externalSvcAdapterRegistrar,
        boolean requestScopedSession) {
        super(agent, externalSvcAdapterRegistrar);
        this.requestScopedSession = requestScopedSession;
    }

    @Override
    protected boolean useRequestScopedSession(ServeRequest request) {
        return requestScopedSession;
    }
}
