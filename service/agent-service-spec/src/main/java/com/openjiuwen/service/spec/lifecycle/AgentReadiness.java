/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

/**
 * Probe readiness state for Agent Service (consumed by {@code GET /health},
 * Issue #8).
 *
 * @since 0.1.0
 */
public interface AgentReadiness {
    /**
     * JVM / HTTP stack is up and accepting lifecycle-managed traffic.
     *
     * @return boolean
     */
    boolean isProcessUp();

    /**
     * Agent / {@link com.openjiuwen.service.spec.spi.AgentHandler} finished init
     * and can serve Query.
     *
     * @return boolean
     */
    boolean isAgentLoaded();
}
