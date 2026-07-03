/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.openjiuwen.service.spec.lifecycle;

/**
 * Optional hook during the AgentApp init phase (after Spring context is ready).
 * <p>
 * Runs after the {@link com.openjiuwen.service.spec.spi.AgentHandler} is
 * loaded; use for
 * warmup or auxiliary setup that depends on a ready handler.
 *
 * @since 0.1.0
 */
public interface AgentInitHook {

    /**
     * Runs during the init phase.
     *
     * @param context the lifecycle context
     * @throws Exception when init hook execution fails
     */
    void onInit(AgentLifecycleContext context) throws Exception;
}
