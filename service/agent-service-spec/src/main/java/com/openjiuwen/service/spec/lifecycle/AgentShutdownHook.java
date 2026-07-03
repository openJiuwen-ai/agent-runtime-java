/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

/**
 * Hook invoked during the AgentApp shutdown phase (before the context closes).
 *
 * @since 0.1.0
 */
public interface AgentShutdownHook {

    /**
     * Runs during the shutdown phase.
     *
     * @param context the lifecycle context
     */
    void onShutdown(AgentLifecycleContext context);
}
