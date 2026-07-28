/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.app.it.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.openjiuwen.service.app.lifecycle.DefaultAgentReadiness;
import com.openjiuwen.service.spec.spi.AgentHandler;

import org.springframework.beans.factory.ObjectProvider;

/**
 * Ensures agent readiness in integration tests where the first init phase may
 * complete before {@link AgentHandler} beans are visible under security auto-configuration.
 */
public final class AgentReadinessTestSupport {
    private AgentReadinessTestSupport() {
    }

    /**
     * Marks readiness when a handler bean is present but init did not flip the flag yet.
     *
     * @param readiness readiness tracker
     * @param agentHandlerProvider handler provider
     */
    public static void ensureAgentLoaded(DefaultAgentReadiness readiness,
        ObjectProvider<AgentHandler> agentHandlerProvider) {
        if (!readiness.isAgentLoaded() && agentHandlerProvider.stream().findFirst().isPresent()) {
            readiness.markAgentLoaded(true);
        }
        assertThat(readiness.isAgentLoaded()).isTrue();
    }
}
