/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.orchestrator;

import com.openjiuwen.service.app.controller.a2a.client.RemoteAgentCall;
import com.openjiuwen.service.spec.dto.QueryChunk;
import com.openjiuwen.service.spec.dto.QueryResponse;
import com.openjiuwen.service.spec.dto.ServeRequest;

import java.util.Optional;

/**
 * Default {@link ServeForwardStrategy} that never forwards. Preserves the
 * legacy orchestrator behaviour when no deployment-module strategy (e.g.
 * {@code ThreeFieldForwardStrategy}) is on the classpath.
 *
 * <p>Registered by {@code A2AAutoConfiguration} as a
 * {@code @ConditionalOnMissingBean(ServeForwardStrategy.class)} bean so
 * deployment modules can override it.
 *
 * @since 0.1.0
 */
public class NoopServeForwardStrategy implements ServeForwardStrategy {
    @Override
    public Optional<RemoteAgentCall> evaluateForward(QueryResponse localResponse, ServeRequest request) {
        return Optional.empty();
    }

    @Override
    public Optional<RemoteAgentCall> interceptStreamEnvelope(QueryChunk chunk, ServeRequest request) {
        return Optional.empty();
    }
}
